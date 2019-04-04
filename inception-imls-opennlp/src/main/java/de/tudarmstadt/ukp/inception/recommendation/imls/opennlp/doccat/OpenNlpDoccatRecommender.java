/*
 * Copyright 2018
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.recommendation.imls.opennlp.doccat;

import static java.util.Arrays.asList;
import static org.apache.uima.fit.util.CasUtil.getAnnotationType;
import static org.apache.uima.fit.util.CasUtil.getType;
import static org.apache.uima.fit.util.CasUtil.indexCovered;
import static org.apache.uima.fit.util.CasUtil.select;
import static org.apache.uima.fit.util.CasUtil.selectCovered;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Stream;

import org.apache.commons.lang3.math.NumberUtils;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.Feature;
import org.apache.uima.cas.Type;
import org.apache.uima.cas.text.AnnotationFS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;
import de.tudarmstadt.ukp.inception.recommendation.api.evaluation.AnnotatedTokenPair;
import de.tudarmstadt.ukp.inception.recommendation.api.evaluation.DataSplitter;
import de.tudarmstadt.ukp.inception.recommendation.api.evaluation.EvaluationResult;
import de.tudarmstadt.ukp.inception.recommendation.api.model.Recommender;
import de.tudarmstadt.ukp.inception.recommendation.api.recommender.RecommendationEngine;
import de.tudarmstadt.ukp.inception.recommendation.api.recommender.RecommendationException;
import de.tudarmstadt.ukp.inception.recommendation.api.recommender.RecommenderContext;
import de.tudarmstadt.ukp.inception.recommendation.api.recommender.RecommenderContext.Key;
import de.tudarmstadt.ukp.inception.recommendation.api.type.PredictedSpan;
import opennlp.tools.doccat.DoccatFactory;
import opennlp.tools.doccat.DoccatModel;
import opennlp.tools.doccat.DocumentCategorizerME;
import opennlp.tools.doccat.DocumentSample;
import opennlp.tools.ml.BeamSearch;
import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.util.TrainingParameters;

public class OpenNlpDoccatRecommender
    implements RecommendationEngine
{
    public static final Key<DoccatModel> KEY_MODEL = new Key<>("model");
    
    private static final Logger LOG = LoggerFactory.getLogger(OpenNlpDoccatRecommender.class);

    private static final String NO_CATEGORY = "<NO_CATEGORY>";

    private final String layerName;
    private final String featureName;
    private final OpenNlpDoccatRecommenderTraits traits;
    private final int maxRecommendations;

    public OpenNlpDoccatRecommender(Recommender aRecommender,
            OpenNlpDoccatRecommenderTraits aTraits)
    {
        layerName = aRecommender.getLayer().getName();
        featureName = aRecommender.getFeature().getName();
        maxRecommendations = aRecommender.getMaxRecommendations();

        traits = aTraits;
    }

    @Override
    public void train(RecommenderContext aContext, List<CAS> aCasses)
        throws RecommendationException
    {
        List<DocumentSample> nameSamples = extractSamples(aCasses);
        
        // The beam size controls how many results are returned at most. But even if the user
        // requests only few results, we always use at least the default bean size recommended by
        // OpenNLP
        int beamSize = Math.max(maxRecommendations, NameFinderME.DEFAULT_BEAM_SIZE);

        TrainingParameters params = traits.getParameters();
        params.put(BeamSearch.BEAM_SIZE_PARAMETER, Integer.toString(beamSize));
        
        DoccatModel model = train(nameSamples, params);
        if (model != null) {
            aContext.put(KEY_MODEL, model);
            aContext.markAsReadyForPrediction();
        }
    }

    @Override
    public void predict(RecommenderContext aContext, CAS aCas) throws RecommendationException
    {
        DoccatModel model = aContext.get(KEY_MODEL).orElseThrow(() -> 
                new RecommendationException("Key [" + KEY_MODEL + "] not found in context"));
        
        DocumentCategorizerME finder = new DocumentCategorizerME(model);

        Type sentenceType = getType(aCas, Sentence.class);
        Type predictionType = getAnnotationType(aCas, PredictedSpan.class);
        Type tokenType = getType(aCas, Token.class);
        Feature confidenceFeature = predictionType.getFeatureByBaseName("score");
        Feature labelFeature = predictionType.getFeatureByBaseName("label");

        int predictionCount = 0;
        for (AnnotationFS sentence : select(aCas, sentenceType)) {
            if (predictionCount >= traits.getPredictionLimit()) {
                break;
            }
            predictionCount++;
            
            List<AnnotationFS> tokenAnnotations = selectCovered(tokenType, sentence);
            String[] tokens = tokenAnnotations.stream()
                .map(AnnotationFS::getCoveredText)
                .toArray(String[]::new);

            double[] outcome = finder.categorize(tokens);
            String label = finder.getBestCategory(outcome);
            
            AnnotationFS annotation = aCas.createAnnotation(predictionType, sentence.getBegin(),
                    sentence.getEnd());
            annotation.setDoubleValue(confidenceFeature, NumberUtils.max(outcome));
            annotation.setStringValue(labelFeature, label);
            aCas.addFsToIndexes(annotation);
        }
    }

    @Override
    public EvaluationResult evaluate(List<CAS> aCasses, DataSplitter aDataSplitter)
        throws RecommendationException
    {
        List<DocumentSample> data = extractSamples(aCasses);
        List<DocumentSample> trainingSet = new ArrayList<>();
        List<DocumentSample> testSet = new ArrayList<>();

        for (DocumentSample nameSample : data) {
            switch (aDataSplitter.getTargetSet(nameSample)) {
            case TRAIN:
                trainingSet.add(nameSample);
                break;
            case TEST:
                testSet.add(nameSample);
                break;
            default:
                // Do nothing
                break;
            }            
        }

        int testSetSize = testSet.size();
        int trainingSetSize = trainingSet.size();
        
        if (trainingSetSize < 2 || testSetSize < 2) {
            LOG.info("Not enough data to evaluate, skipping!");
            
            EvaluationResult result = new EvaluationResult(null, null, trainingSetSize,
                    testSetSize);
            result.setEvaluationSkipped(true);
            return result;
        }

        LOG.info("Evaluating on {} items (training set size {}, test set size {})", data.size(),
                trainingSet.size(), testSet.size());

        // Train model
        DoccatModel model = train(trainingSet, traits.getParameters());
        DocumentCategorizerME doccat = new DocumentCategorizerME(model);

        // Evaluate
        Stream<AnnotatedTokenPair> predictions = testSet.stream()
                .map(sample -> new AnnotatedTokenPair(sample.getCategory(),
                        doccat.getBestCategory(doccat.categorize(sample.getText()))));
        
        return new EvaluationResult(asList(NO_CATEGORY), predictions, trainingSetSize, testSetSize);
    }

    private List<DocumentSample> extractSamples(List<CAS> aCasses)
    {
        List<DocumentSample> samples = new ArrayList<>();
        casses: for (CAS cas : aCasses) {
            Type sentenceType = getType(cas, Sentence.class);
            Type tokenType = getType(cas, Token.class);

            Map<AnnotationFS, Collection<AnnotationFS>> sentences = indexCovered(
                    cas, sentenceType, tokenType);
            for (Entry<AnnotationFS, Collection<AnnotationFS>> e : sentences.entrySet()) {
                AnnotationFS sentence = e.getKey();
                Collection<AnnotationFS> tokens = e.getValue();
                String[] tokenTexts = tokens.stream()
                    .map(AnnotationFS::getCoveredText)
                    .toArray(String[]::new);
                
                Type annotationType = getType(cas, layerName);
                Feature feature = annotationType.getFeatureByBaseName(featureName);
                
                for (AnnotationFS annotation : selectCovered(annotationType, sentence)) {
                    if (samples.size() >= traits.getTrainingSetSizeLimit()) {
                        break casses;
                    }
                    
                    String label = annotation.getFeatureValueAsString(feature);
                    DocumentSample nameSample = new DocumentSample(
                            label != null ? label : NO_CATEGORY, tokenTexts);
                    if (nameSample.getCategory() != null) {
                        samples.add(nameSample);
                    }
                }
            }
        }
        
        return samples;
    }

    private DoccatModel train(List<DocumentSample> aSamples, TrainingParameters aParameters)
        throws RecommendationException
    {
        try (DocumentSampleStream stream = new DocumentSampleStream(aSamples)) {
            DoccatFactory factory = new DoccatFactory();
            return DocumentCategorizerME.train("unknown", stream, aParameters, factory);
        }
        catch (IOException e) {
            throw new RecommendationException(
                    "Exception during training the OpenNLP Document Categorizer model.", e);
        }
    }
}
