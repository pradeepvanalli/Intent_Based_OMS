package ai.intentoms.learning.model;

/** What a proposal targets, e.g. {@code decision:sourcing_node_score} or {@code parameter:node_reliability}. */
public record ArtifactRef(String type, String name) {
    public static ArtifactRef parse(String ref) {
        int i = ref.indexOf(':');
        if (i < 0) throw new IllegalArgumentException("Artifact ref must be \"type:name\": " + ref);
        return new ArtifactRef(ref.substring(0, i), ref.substring(i + 1));
    }
    @Override public String toString() { return type + ":" + name; }
}
