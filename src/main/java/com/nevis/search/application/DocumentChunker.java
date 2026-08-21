package com.nevis.search.application;

import com.nevis.search.application.embedding.EmbeddingModelCapabilities;
import com.nevis.search.application.port.EmbeddingTokenizerPort;
import com.nevis.search.config.DocumentChunkingProperties;
import org.springframework.stereotype.Component;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class DocumentChunker {

    private static final String TITLE_SEPARATOR = "\n\n";
    private static final Pattern PARAGRAPH_SEPARATOR = Pattern.compile("(?:\\R[\\t ]*){2,}");

    private final EmbeddingTokenizerPort tokenizer;
    private final DocumentChunkingProperties properties;

    public DocumentChunker(
            EmbeddingTokenizerPort tokenizer,
            DocumentChunkingProperties properties,
            EmbeddingModelCapabilities capabilities
    ) {
        this.tokenizer = tokenizer;
        this.properties = properties;
        if (properties.maxInputTokens() > capabilities.maxInputTokens()) {
            throw new IllegalArgumentException(
                    "Configured chunk input limit exceeds model capability for " + capabilities.modelId()
            );
        }
        int minimumBodyBudget = properties.maxInputTokens()
                - properties.maxTitleTokens()
                - tokenizer.countTokens(TITLE_SEPARATOR);
        if (minimumBodyBudget < 1 || properties.overlapTokens() >= minimumBodyBudget) {
            throw new IllegalArgumentException("Chunk overlap must be less than the usable body budget");
        }
    }

    public List<Chunk> chunk(String title, String content) {
        String semanticTitle = truncateTitle(title);
        int headerTokens = tokenizer.countTokens(semanticTitle + TITLE_SEPARATOR);
        int bodyCapacity = properties.maxInputTokens() - headerTokens;
        int uniqueTokenBudget = Math.max(1, bodyCapacity - properties.overlapTokens());
        List<Unit> units = createUnits(semanticTitle, content, uniqueTokenBudget);
        if (units.isEmpty()) {
            throw new IllegalArgumentException("Document content must contain model tokens");
        }

        List<ChunkDraft> drafts = packUnits(semanticTitle, units);
        List<Chunk> chunks = new ArrayList<>(drafts.size());
        for (int index = 0; index < drafts.size(); index++) {
            String body = drafts.get(index).body();
            String input = embeddingInput(semanticTitle, body);
            if (body.isBlank() || tokenizer.countTokens(input) > properties.maxInputTokens()) {
                throw new IllegalStateException("Chunker produced an invalid embedding input");
            }
            chunks.add(new Chunk(index, body, input));
        }
        return List.copyOf(chunks);
    }

    public String truncateTitle(String title) {
        int tokens = tokenizer.countTokens(title);
        if (tokens <= properties.maxTitleTokens()) {
            return title;
        }
        return tokenizer.slice(title, 0, properties.maxTitleTokens()).stripTrailing();
    }

    public String embeddingInput(String semanticTitle, String chunkBody) {
        return semanticTitle + TITLE_SEPARATOR + chunkBody;
    }

    private List<Unit> createUnits(String semanticTitle, String content, int uniqueTokenBudget) {
        String[] paragraphs = PARAGRAPH_SEPARATOR.split(content);
        List<Unit> units = new ArrayList<>();
        for (int paragraphIndex = 0; paragraphIndex < paragraphs.length; paragraphIndex++) {
            String paragraph = paragraphs[paragraphIndex].strip();
            if (paragraph.isEmpty()) {
                continue;
            }
            if (fits(semanticTitle, paragraph)) {
                units.add(new Unit(paragraph, paragraphIndex));
                continue;
            }
            for (String sentence : sentences(paragraph)) {
                if (fits(semanticTitle, sentence)) {
                    units.add(new Unit(sentence, paragraphIndex));
                } else {
                    int tokenCount = tokenizer.countTokens(sentence);
                    for (int start = 0; start < tokenCount; start += uniqueTokenBudget) {
                        int end = Math.min(tokenCount, start + uniqueTokenBudget);
                        String window = tokenizer.slice(sentence, start, end).strip();
                        if (!window.isEmpty()) {
                            units.add(new Unit(window, paragraphIndex));
                        }
                    }
                }
            }
        }
        return units;
    }

    private List<ChunkDraft> packUnits(String semanticTitle, List<Unit> units) {
        List<ChunkDraft> chunks = new ArrayList<>();
        String currentBody = null;
        int currentParagraph = -1;
        for (Unit unit : units) {
            if (currentBody == null) {
                currentBody = startChunk(semanticTitle, previousBody(chunks), unit.text());
                currentParagraph = unit.paragraphIndex();
                continue;
            }
            String separator = currentParagraph == unit.paragraphIndex() ? " " : TITLE_SEPARATOR;
            String candidate = currentBody + separator + unit.text();
            if (fits(semanticTitle, candidate)) {
                currentBody = candidate;
                currentParagraph = unit.paragraphIndex();
            } else {
                chunks.add(new ChunkDraft(currentBody));
                currentBody = startChunk(semanticTitle, previousBody(chunks), unit.text());
                currentParagraph = unit.paragraphIndex();
            }
        }
        if (currentBody != null) {
            chunks.add(new ChunkDraft(currentBody));
        }
        return chunks;
    }

    private String startChunk(String semanticTitle, String previousBody, String uniqueContent) {
        if (previousBody == null || properties.overlapTokens() == 0) {
            return uniqueContent;
        }
        String naturalOverlap = trailingSentences(previousBody);
        if (!naturalOverlap.isEmpty()) {
            String candidate = naturalOverlap + TITLE_SEPARATOR + uniqueContent;
            if (fits(semanticTitle, candidate)) {
                return candidate;
            }
        }
        int previousTokens = tokenizer.countTokens(previousBody);
        int maximumOverlap = Math.min(properties.overlapTokens(), previousTokens);
        for (int overlap = maximumOverlap; overlap > 0; overlap--) {
            String tokenTail = tokenizer.slice(previousBody, previousTokens - overlap, previousTokens).strip();
            if (!tokenTail.isEmpty()) {
                String candidate = tokenTail + TITLE_SEPARATOR + uniqueContent;
                if (fits(semanticTitle, candidate)) {
                    return candidate;
                }
            }
        }
        if (!fits(semanticTitle, uniqueContent)) {
            throw new IllegalStateException("A chunk unit exceeds the configured token limit");
        }
        return uniqueContent;
    }

    private String trailingSentences(String body) {
        List<String> sentences = sentences(body);
        String tail = "";
        for (int index = sentences.size() - 1; index >= 0; index--) {
            String candidate = tail.isEmpty() ? sentences.get(index) : sentences.get(index) + " " + tail;
            if (tokenizer.countTokens(candidate) > properties.overlapTokens()) {
                break;
            }
            tail = candidate;
        }
        return tail;
    }

    private List<String> sentences(String paragraph) {
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ENGLISH);
        iterator.setText(paragraph);
        List<String> sentences = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = paragraph.substring(start, end).strip();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }
        if (sentences.isEmpty() && !paragraph.isBlank()) {
            sentences.add(paragraph.strip());
        }
        return sentences;
    }

    private boolean fits(String semanticTitle, String body) {
        return tokenizer.countTokens(embeddingInput(semanticTitle, body)) <= properties.maxInputTokens();
    }

    private String previousBody(List<ChunkDraft> chunks) {
        return chunks.isEmpty() ? null : chunks.getLast().body();
    }

    public record Chunk(int index, String body, String embeddingInput) {
    }

    private record Unit(String text, int paragraphIndex) {
    }

    private record ChunkDraft(String body) {
    }
}
