package com.productsearch.infra;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;
import io.pinecone.unsigned_indices_model.QueryResponseWithUnsignedIndices;
import io.pinecone.unsigned_indices_model.ScoredVectorWithUnsignedIndices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class PineconeIndex {

    public static final String CATEGORY_NAMESPACE = "categorynamespace";
    public static final String PRODUCTS_NAMESPACE = "productsnamespace";

    public record Match(String id, float score, Map<String, Object> metadata) {}

    private final Index index;

    public PineconeIndex(SecretManagerService secrets,
                         @org.springframework.beans.factory.annotation.Value("${pinecone.index.name}") String indexName) {
        this.index = new Pinecone.Builder(secrets.getPineconeApiKey()).build().getIndexConnection(indexName);
        log.info("Pinecone client initialised for index '{}'", indexName);
    }

    public void upsert(String namespace, String id, List<Float> vector, Map<String, Object> metadata) {
        index.upsert(id, vector, null, null, toStruct(metadata), namespace);
    }

    public List<Match> query(String namespace, List<Float> vector, int topK,
                             double minScore, Map<String, Object> filter) {
        Struct filterStruct = (filter == null || filter.isEmpty()) ? null : toStruct(filter);
        QueryResponseWithUnsignedIndices resp =
                index.queryByVector(topK, vector, namespace, filterStruct, false, true);
        List<ScoredVectorWithUnsignedIndices> raw = resp.getMatchesList();
        List<Match> matches = new ArrayList<>(raw.size());
        for (ScoredVectorWithUnsignedIndices m : raw) {
            if (m.getScore() >= minScore) {
                matches.add(new Match(m.getId(), m.getScore(), fromStruct(m.getMetadata())));
            }
        }
        return matches;
    }

    private static Struct toStruct(Map<String, Object> map) {
        if (map == null) return Struct.getDefaultInstance();
        Struct.Builder b = Struct.newBuilder();
        map.forEach((k, v) -> b.putFields(k, toValue(v)));
        return b.build();
    }

    private static Value toValue(Object o) {
        Value.Builder v = Value.newBuilder();
        if (o == null) v.setNullValue(NullValue.NULL_VALUE);
        else if (o instanceof String s)      v.setStringValue(s);
        else if (o instanceof Boolean b)     v.setBoolValue(b);
        else if (o instanceof Number n)      v.setNumberValue(n.doubleValue());
        else if (o instanceof Map<?, ?> m) {
            Struct.Builder sb = Struct.newBuilder();
            m.forEach((k, val) -> sb.putFields(String.valueOf(k), toValue(val)));
            v.setStructValue(sb.build());
        } else if (o instanceof Iterable<?> it) {
            ListValue.Builder lb = ListValue.newBuilder();
            it.forEach(x -> lb.addValues(toValue(x)));
            v.setListValue(lb.build());
        } else {
            v.setStringValue(o.toString());
        }
        return v.build();
    }

    private static Map<String, Object> fromStruct(Struct s) {
        Map<String, Object> out = new HashMap<>();
        if (s == null) return out;
        s.getFieldsMap().forEach((k, val) -> out.put(k, fromValue(val)));
        return out;
    }

    private static Object fromValue(Value v) {
        return switch (v.getKindCase()) {
            case STRING_VALUE -> v.getStringValue();
            case NUMBER_VALUE -> v.getNumberValue();
            case BOOL_VALUE   -> v.getBoolValue();
            case STRUCT_VALUE -> fromStruct(v.getStructValue());
            case LIST_VALUE   -> v.getListValue().getValuesList().stream().map(PineconeIndex::fromValue).toList();
            default           -> null;
        };
    }
}
