package ai.intentoms.order.model;

/** Postal address. type: ship_to | bill_to | pickup. Verification outcome recorded by the address validation step. */
public record Address(String type, String name, String line1, String line2, String city, String state, String zip, String country,
                      String phone, String addressType /*residential|commercial|po_box*/, boolean verified, Double lat, Double lon) {
    public Address withVerified(boolean v, Double la, Double lo) { return new Address(type, name, line1, line2, city, state, zip, country, phone, addressType, v, la, lo); }
    public String oneLine() { return String.join(", ", java.util.stream.Stream.of(line1, line2, city, state, zip, country).filter(s -> s != null && !s.isBlank()).toList()); }
}
