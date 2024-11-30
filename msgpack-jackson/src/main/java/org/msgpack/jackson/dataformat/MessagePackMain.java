package org.msgpack.jackson.dataformat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.MappingJsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

public class MessagePackMain {
    private static final String SAMPLE_JSON = "        {\n" +
            "            \"glossary\": {\n" +
            "                \"title\": \"example glossary\",\n" +
            "                \"GlossDiv\": {\n" +
            "                    \"title\": \"S\",\n" +
            "                    \"GlossList\": {\n" +
            "                        \"GlossEntry\": {\n" +
            "                            \"ID\": \"SGML\",\n" +
            "                            \"SortAs\": \"SGML\",\n" +
            "                            \"GlossTerm\": \"Standard Generalized Markup Language\",\n" +
            "                            \"Acronym\": \"SGML\",\n" +
            "                            \"Abbrev\": \"ISO 8879:1986\",\n" +
            "                            \"GlossDef\": {\n" +
            "                                \"para\": \"A meta-markup language, used to create markup languages such as DocBook.\",\n" +
            "                                \"GlossSeeAlso\": [\"GML\", \"XML\"]\n" +
            "                            },\n" +
            "                            \"GlossSee\": \"markup\"\n" +
            "                        }\n" +
            "                    }\n" +
            "                }\n" +
            "            }\n" +
            "        }\n";

    private static final String SAMPLE_JSON2 = "{" +
            "\"s0\": \"0000000000000000\", " +
            "\"s1\": \"0000000000000000\", " +
            "\"s2\": \"0000000000000000\", " +
            "\"s3\": \"0000000000000000\", " +
            "\"s4\": \"0000000000000000\", " +
            "\"s5\": \"0000000000000000\", " +
            "\"s6\": \"0000000000000000\", " +
            "\"s7\": \"0000000000000000\", " +
            "\"s8\": \"0000000000000000\", " +
            "\"s9\": \"0000000000000000\" " +
            "}";

    public static void main(String[] args) throws IOException {
        testMessagePackSer();
        testMessagePackSer();
    }

    public static void test() throws IOException {
        ObjectMapper jsonMapper = new ObjectMapper();
        ObjectMapper messagePackMapper = new MessagePackMapper();
        int iterations = 5_000_000;
//        int iterations = 1;

        Item item = new ObjectMapper().readValue(SAMPLE_JSON, Item.class);

        System.out.println("==================================== OUTPUT ====================================");

        long startTime = System.currentTimeMillis();
        byte[] jsonOutput = null;
        byte[] messagePackOutput = null;

        for (int i =0; i < iterations; i++) {
            byte[] bytes = jsonMapper.writeValueAsBytes(item);
            if (jsonOutput == null) {
                jsonOutput = bytes;
            }
        }

        long endTime = System.currentTimeMillis();
        long jSerializeElapsedTime = endTime - startTime;
        System.out.println("JSON serialized " + NumberFormat.getNumberInstance(Locale.US).format(iterations) + " times in " + jSerializeElapsedTime + "ms");
        System.out.println("JSON size " + jsonOutput.length);

        startTime = System.currentTimeMillis();

        for (int i =0; i < iterations; i++) {
            byte[] bytes = messagePackMapper.writeValueAsBytes(item);
            if (messagePackOutput == null) {
                messagePackOutput = bytes;
            }
        }

        endTime = System.currentTimeMillis();
        long mSerializeElapsedTime = endTime - startTime;
        System.out.println("MessagePack serialized " + NumberFormat.getNumberInstance(Locale.US).format(iterations) + " times in " + mSerializeElapsedTime + "ms");
        System.out.println("MessagePack size " + messagePackOutput.length);

        String newJson = new String(jsonOutput);
        startTime = System.currentTimeMillis();

        for (int i =0; i < iterations; i++) {
            jsonMapper.readValue(newJson, Item.class);
        }

        endTime = System.currentTimeMillis();
        long jDeserializeElapsedTime = endTime - startTime;
        System.out.println("JSON deserialized " + NumberFormat.getNumberInstance(Locale.US).format(iterations) + " times in " + jDeserializeElapsedTime + "ms");

        startTime = System.currentTimeMillis();

        for (int i =0; i < iterations; i++) {
            messagePackMapper.readValue(messagePackOutput, Item.class);
        }

        endTime = System.currentTimeMillis();
        long mDeserializeElapsedTime = endTime - startTime;
        System.out.println("MessagePack deserialized " + NumberFormat.getNumberInstance(Locale.US).format(iterations) + " times in " + mDeserializeElapsedTime + "ms");

        System.out.println("\n=================================== SUMMARY ====================================");

        if (jSerializeElapsedTime > mSerializeElapsedTime) {
            System.out.printf("MessagePack serialization is %%%.02f faster than JSON serialization\n", ((1.0 - (float) mSerializeElapsedTime / (float) jSerializeElapsedTime))*100.0);
        } else {
            System.out.printf("JSON serialization is %%%.02f faster than MessagePack serialization\n", ((1.0 - (float) jSerializeElapsedTime / (float) mSerializeElapsedTime))*100.0);
        }

        if (jDeserializeElapsedTime > mDeserializeElapsedTime) {
            System.out.printf("MessagePack deserialization is %%%.02f faster than JSON deserialization\n", ((1.0 - (float) mDeserializeElapsedTime / (float) jDeserializeElapsedTime))*100.0);
        } else {
            System.out.printf("JSON deserialization is %%%.02f faster than MessagePack deserialization\n", ((1.0 - (float) jDeserializeElapsedTime / (float) mDeserializeElapsedTime))*100.0);
        }

        if (jsonOutput.length > messagePackOutput.length) {
            System.out.printf("MessagePack is %%%.02f smaller than JSON\n", ((1.0 - (float) messagePackOutput.length / (float)jsonOutput.length))*100.0);
        } else {
            System.out.printf("JSON is %%%.02f smaller than MessagePack\n", ((1.0 - (float) jsonOutput.length / (float)messagePackOutput.length))*100.0);
        }
    }

    public static void test2() throws IOException {
        JsonFactory jsonFactory = new MappingJsonFactory().configure(JsonFactory.Feature.USE_THREAD_LOCAL_FOR_BUFFER_RECYCLING, true);
        ObjectMapper jsonMapper = new ObjectMapper(jsonFactory);
        ObjectMapper messagePackMapper = new MessagePackMapper();
        int iterations = 2_000_000;
//        int iterations = 2;

        Item2 item = new ObjectMapper().readValue(SAMPLE_JSON2, Item2.class);

        System.out.println("==================================== OUTPUT ====================================");

        long startTime = System.currentTimeMillis();
        byte[] jsonOutput = null;
        byte[] messagePackOutput = null;

        for (int i =0; i < iterations; i++) {
            byte[] bytes = jsonMapper.writeValueAsBytes(item);
            if (jsonOutput == null) {
                jsonOutput = bytes;
            }
        }

        long endTime = System.currentTimeMillis();
        long jSerializeElapsedTime = endTime - startTime;
        System.out.println("JSON serialized " + NumberFormat.getNumberInstance(Locale.US).format(iterations) + " times in " + jSerializeElapsedTime + "ms");
        System.out.println("JSON size " + jsonOutput.length);

        startTime = System.currentTimeMillis();

        for (int i =0; i < iterations; i++) {
            byte[] bytes = messagePackMapper.writeValueAsBytes(item);
            if (messagePackOutput == null) {
                messagePackOutput = bytes;
            }
        }

        endTime = System.currentTimeMillis();
        long mSerializeElapsedTime = endTime - startTime;
        System.out.println("MessagePack serialized " + NumberFormat.getNumberInstance(Locale.US).format(iterations) + " times in " + mSerializeElapsedTime + "ms");
        System.out.println("MessagePack size " + messagePackOutput.length);

        String newJson = new String(jsonOutput);
        startTime = System.currentTimeMillis();

        for (int i =0; i < iterations; i++) {
            jsonMapper.readValue(newJson, Item2.class);
        }

        endTime = System.currentTimeMillis();
        long jDeserializeElapsedTime = endTime - startTime;
        System.out.println("JSON deserialized " + NumberFormat.getNumberInstance(Locale.US).format(iterations) + " times in " + jDeserializeElapsedTime + "ms");

        startTime = System.currentTimeMillis();

        for (int i =0; i < iterations; i++) {
            messagePackMapper.readValue(messagePackOutput, Item2.class);
        }

        endTime = System.currentTimeMillis();
        long mDeserializeElapsedTime = endTime - startTime;
        System.out.println("MessagePack deserialized " + NumberFormat.getNumberInstance(Locale.US).format(iterations) + " times in " + mDeserializeElapsedTime + "ms");

        System.out.println("\n=================================== SUMMARY ====================================");

        if (jSerializeElapsedTime > mSerializeElapsedTime) {
            System.out.printf("MessagePack serialization is %%%.02f faster than JSON serialization\n", ((1.0 - (float) mSerializeElapsedTime / (float) jSerializeElapsedTime))*100.0);
        } else {
            System.out.printf("JSON serialization is %%%.02f faster than MessagePack serialization\n", ((1.0 - (float) jSerializeElapsedTime / (float) mSerializeElapsedTime))*100.0);
        }

        if (jDeserializeElapsedTime > mDeserializeElapsedTime) {
            System.out.printf("MessagePack deserialization is %%%.02f faster than JSON deserialization\n", ((1.0 - (float) mDeserializeElapsedTime / (float) jDeserializeElapsedTime))*100.0);
        } else {
            System.out.printf("JSON deserialization is %%%.02f faster than MessagePack deserialization\n", ((1.0 - (float) jDeserializeElapsedTime / (float) mDeserializeElapsedTime))*100.0);
        }

        if (jsonOutput.length > messagePackOutput.length) {
            System.out.printf("MessagePack is %%%.02f smaller than JSON\n", ((1.0 - (float) messagePackOutput.length / (float)jsonOutput.length))*100.0);
        } else {
            System.out.printf("JSON is %%%.02f smaller than MessagePack\n", ((1.0 - (float) jsonOutput.length / (float)messagePackOutput.length))*100.0);
        }
    }

    public static void testMessagePackSer() throws IOException {
        ObjectMapper messagePackMapper = new MessagePackMapper();
        int iterations = 10_000_000;
//        int iterations = 2;

        Item2 item = new ObjectMapper().readValue(SAMPLE_JSON2, Item2.class);

        System.out.println("==================================== OUTPUT ====================================");

        long startTime = System.currentTimeMillis();
        byte[] messagePackOutput = null;

        startTime = System.currentTimeMillis();

        for (int i =0; i < iterations; i++) {
            byte[] bytes = messagePackMapper.writeValueAsBytes(item);
            if (messagePackOutput == null) {
                messagePackOutput = bytes;
            }
        }

        long endTime = System.currentTimeMillis();
        long mSerializeElapsedTime = endTime - startTime;
        System.out.println("MessagePack serialized " + NumberFormat.getNumberInstance(Locale.US).format(iterations) + " times in " + mSerializeElapsedTime + "ms");
        System.out.println("MessagePack size " + messagePackOutput.length);
    }

    public static void testJsonSer() throws IOException {
        JsonFactory jsonFactory = new MappingJsonFactory().configure(JsonFactory.Feature.USE_THREAD_LOCAL_FOR_BUFFER_RECYCLING, true);
        ObjectMapper jsonMapper = new ObjectMapper(jsonFactory);
        int iterations = 10_000_000;
//        int iterations = 2;

        Item2 item = new ObjectMapper().readValue(SAMPLE_JSON2, Item2.class);

        System.out.println("==================================== OUTPUT ====================================");

        long startTime = System.currentTimeMillis();
        byte[] jsonOutput = null;
        byte[] messagePackOutput = null;

        for (int i =0; i < iterations; i++) {
            byte[] bytes = jsonMapper.writeValueAsBytes(item);
            if (jsonOutput == null) {
                jsonOutput = bytes;
            }
        }

        long endTime = System.currentTimeMillis();
        long jSerializeElapsedTime = endTime - startTime;
        System.out.println("JSON serialized " + NumberFormat.getNumberInstance(Locale.US).format(iterations) + " times in " + jSerializeElapsedTime + "ms");
        System.out.println("JSON size " + jsonOutput.length);
    }

    private static class Item {
        private Glossary glossary;

        public Glossary getGlossary() {
            return glossary;
        }

        public void setGlossary(Glossary glossary) {
            this.glossary = glossary;
        }
    }

    private static class GlossaryDef {
        private String para;

        @JsonProperty("GlossSeeAlso")
        private List<String> seeAlso;

        public String getPara() {
            return para;
        }

        public void setPara(String para) {
            this.para = para;
        }

        public List<String> getSeeAlso() {
            return seeAlso;
        }

        public void setSeeAlso(List<String> seeAlso) {
            this.seeAlso = seeAlso;
        }
    }

    private static class GlossaryEntry {
        @JsonProperty("ID")
        private String id;

        @JsonProperty("SortAs")
        private String sortAs;

        @JsonProperty("GlossTerm")
        private String glossTerm;

        @JsonProperty("Acronym")
        private String acronym;

        @JsonProperty("Abbrev")
        private String abbrev;

        @JsonProperty("GlossDef")
        private GlossaryDef glossaryDef;

        @JsonProperty("GlossSee")
        private String glossSee;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getSortAs() {
            return sortAs;
        }

        public void setSortAs(String sortAs) {
            this.sortAs = sortAs;
        }

        public String getGlossTerm() {
            return glossTerm;
        }

        public void setGlossTerm(String glossTerm) {
            this.glossTerm = glossTerm;
        }

        public String getAcronym() {
            return acronym;
        }

        public void setAcronym(String acronym) {
            this.acronym = acronym;
        }

        public String getAbbrev() {
            return abbrev;
        }

        public void setAbbrev(String abbrev) {
            this.abbrev = abbrev;
        }

        public GlossaryDef getGlossaryDef() {
            return glossaryDef;
        }

        public void setGlossaryDef(GlossaryDef glossaryDef) {
            this.glossaryDef = glossaryDef;
        }

        public String getGlossSee() {
            return glossSee;
        }

        public void setGlossSee(String glossSee) {
            this.glossSee = glossSee;
        }
    }

    private static class GlossaryList {
        @JsonProperty("GlossEntry")
        private GlossaryEntry glossaryEntry;

        public GlossaryEntry getGlossaryEntry() {
            return glossaryEntry;
        }

        public void setGlossaryEntry(GlossaryEntry glossaryEntry) {
            this.glossaryEntry = glossaryEntry;
        }
    }

    private static class GlossaryDiv {
        private String title;

        @JsonProperty("GlossList")
        private GlossaryList glossaryList;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public GlossaryList getGlossaryList() {
            return glossaryList;
        }

        public void setGlossaryList(GlossaryList glossaryList) {
            this.glossaryList = glossaryList;
        }
    }

    private static class Glossary {
        private String title;

        @JsonProperty("GlossDiv")
        private GlossaryDiv glossaryDiv;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public GlossaryDiv getGlossaryDiv() {
            return glossaryDiv;
        }

        public void setGlossaryDiv(GlossaryDiv glossaryDiv) {
            this.glossaryDiv = glossaryDiv;
        }
    }

    private static class Item2 {
        @JsonProperty("s0")
        private String s0;
        @JsonProperty("s1")
        private String s1;
        @JsonProperty("s2")
        private String s2;
        @JsonProperty("s3")
        private String s3;
        @JsonProperty("s4")
        private String s4;
        @JsonProperty("s5")
        private String s5;
        @JsonProperty("s6")
        private String s6;
        @JsonProperty("s7")
        private String s7;
        @JsonProperty("s8")
        private String s8;
        @JsonProperty("s9")
        private String s9;
    }
}