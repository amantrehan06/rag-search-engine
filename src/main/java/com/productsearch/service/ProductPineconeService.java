package com.productsearch.service;

import com.productsearch.infra.PineconeIndex;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProductPineconeService {

    private static final double MIN_SCORE = 0.20;

    private final OpenAiEmbeddingModel embeddingModel;
    private final PineconeIndex pinecone;

    public List<PineconeIndex.Match> search(String query, int maxResults, Map<String, Object> filter) {
        var vector = embeddingModel.embed(query).content().vectorAsList();
        return pinecone.query(PineconeIndex.PRODUCTS_NAMESPACE, vector, maxResults, MIN_SCORE, filter);
    }
}
