package ai.intentoms.platform.ledger;

/** Who caused an event: "user:alice", "agent:shopbot", "system:sweeper", "decision:<record-id>". */
public record Actor(String type, String id) {
    public static Actor parse(String ref) {
        int i = ref.indexOf(':');
        return i < 0 ? new Actor("system", ref) : new Actor(ref.substring(0, i), ref.substring(i + 1));
    }
    public static Actor system(String id) { return new Actor("system", id); }
    public static Actor user(String id) { return new Actor("user", id); }
    public static Actor agent(String id) { return new Actor("agent", id); }
    public static Actor decision(String recordId) { return new Actor("decision", recordId); }
    public String ref() { return type + ":" + id; }
}
