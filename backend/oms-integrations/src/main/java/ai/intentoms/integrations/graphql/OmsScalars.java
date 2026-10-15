package ai.intentoms.integrations.graphql;

import ai.intentoms.platform.json.Json;
import graphql.language.ArrayValue;
import graphql.language.BooleanValue;
import graphql.language.EnumValue;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.NullValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom scalars of the OMS schema (FR-CAP-01): {@code DateTime} (ISO instant), {@code Date} (ISO local date),
 * {@code Decimal} (money amounts, serialised as a JSON number) and {@code JSON} (extensions / attributes / explain).
 * Written in-module so the graphql-java version is the one Spring Boot manages.
 */
@Configuration
public class OmsScalars {
    public static final GraphQLScalarType DATE_TIME = GraphQLScalarType.newScalar().name("DateTime").description("ISO-8601 instant").coercing(new Coercing<Instant, String>() {
        @Override public String serialize(Object o) {
            if (o instanceof Instant i) return i.toString();
            if (o instanceof OffsetDateTime odt) return odt.toInstant().toString();
            if (o instanceof String s) return parse(s).toString();
            throw new CoercingSerializeException("Not a DateTime: " + o);
        }
        @Override public Instant parseValue(Object o) {
            if (o instanceof Instant i) return i;
            if (o instanceof String s) try { return parse(s); } catch (DateTimeParseException e) { throw new CoercingParseValueException("Invalid DateTime '" + s + "'"); }
            throw new CoercingParseValueException("Not a DateTime: " + o);
        }
        @Override public Instant parseLiteral(Object o) {
            if (o instanceof StringValue s) try { return parse(s.getValue()); } catch (DateTimeParseException e) { throw new CoercingParseLiteralException("Invalid DateTime '" + s.getValue() + "'"); }
            throw new CoercingParseLiteralException("DateTime literal must be a string");
        }
        private Instant parse(String s) { try { return Instant.parse(s); } catch (DateTimeParseException e) { return OffsetDateTime.parse(s).toInstant(); } }
    }).build();

    public static final GraphQLScalarType DATE = GraphQLScalarType.newScalar().name("Date").description("ISO-8601 local date").coercing(new Coercing<LocalDate, String>() {
        @Override public String serialize(Object o) {
            if (o instanceof LocalDate d) return d.toString();
            if (o instanceof String s) return LocalDate.parse(s).toString();
            throw new CoercingSerializeException("Not a Date: " + o);
        }
        @Override public LocalDate parseValue(Object o) {
            if (o instanceof LocalDate d) return d;
            if (o instanceof String s) try { return LocalDate.parse(s); } catch (DateTimeParseException e) { throw new CoercingParseValueException("Invalid Date '" + s + "'"); }
            throw new CoercingParseValueException("Not a Date: " + o);
        }
        @Override public LocalDate parseLiteral(Object o) {
            if (o instanceof StringValue s) try { return LocalDate.parse(s.getValue()); } catch (DateTimeParseException e) { throw new CoercingParseLiteralException("Invalid Date '" + s.getValue() + "'"); }
            throw new CoercingParseLiteralException("Date literal must be a string");
        }
    }).build();

    public static final GraphQLScalarType DECIMAL = GraphQLScalarType.newScalar().name("Decimal").description("Arbitrary-precision decimal").coercing(new Coercing<BigDecimal, BigDecimal>() {
        @Override public BigDecimal serialize(Object o) {
            if (o instanceof BigDecimal b) return b;
            if (o instanceof Number n) return new BigDecimal(n.toString());
            if (o instanceof String s) try { return new BigDecimal(s); } catch (NumberFormatException e) { throw new CoercingSerializeException("Not a Decimal: " + s); }
            throw new CoercingSerializeException("Not a Decimal: " + o);
        }
        @Override public BigDecimal parseValue(Object o) {
            try {
                if (o instanceof BigDecimal b) return b;
                if (o instanceof Number n) return new BigDecimal(n.toString());
                if (o instanceof String s) return new BigDecimal(s.trim());
            } catch (NumberFormatException e) { throw new CoercingParseValueException("Invalid Decimal '" + o + "'"); }
            throw new CoercingParseValueException("Not a Decimal: " + o);
        }
        @Override public BigDecimal parseLiteral(Object o) {
            try {
                if (o instanceof IntValue i) return new BigDecimal(i.getValue());
                if (o instanceof FloatValue f) return f.getValue();
                if (o instanceof StringValue s) return new BigDecimal(s.getValue().trim());
            } catch (NumberFormatException e) { throw new CoercingParseLiteralException("Invalid Decimal literal"); }
            throw new CoercingParseLiteralException("Decimal literal must be a number or string");
        }
    }).build();

    public static final GraphQLScalarType JSON = GraphQLScalarType.newScalar().name("JSON").description("Free-form JSON").coercing(new Coercing<Object, Object>() {
        @Override public Object serialize(Object o) { return o instanceof Map || o instanceof List || o instanceof String || o instanceof Number || o instanceof Boolean || o == null ? o : Json.toMap(o); }
        @Override public Object parseValue(Object o) { return o; }
        @Override public Object parseLiteral(Object o) { return literal(o); }
        @Override public Object parseLiteral(Object input, Map<String, Object> variables) { return literal(input, variables); }
    }).build();

    static Object literal(Object v) { return literal(v, Map.of()); }

    static Object literal(Object v, Map<String, Object> variables) {
        if (!(v instanceof Value<?> value)) return v;
        if (value instanceof NullValue) return null;
        if (value instanceof StringValue s) return s.getValue();
        if (value instanceof IntValue i) return i.getValue().bitLength() < 63 ? (Object) i.getValue().longValue() : i.getValue();
        if (value instanceof FloatValue f) return f.getValue();
        if (value instanceof BooleanValue b) return b.isValue();
        if (value instanceof EnumValue e) return e.getName();
        if (value instanceof graphql.language.VariableReference ref) return variables.get(ref.getName());
        if (value instanceof ArrayValue a) { List<Object> out = new ArrayList<>(); for (Value<?> x : a.getValues()) out.add(literal(x, variables)); return out; }
        if (value instanceof ObjectValue o) { Map<String, Object> out = new LinkedHashMap<>(); for (ObjectField f : o.getObjectFields()) out.put(f.getName(), literal(f.getValue(), variables)); return out; }
        throw new CoercingParseLiteralException("Unsupported JSON literal " + value.getClass().getSimpleName());
    }

    @Bean
    RuntimeWiringConfigurer omsScalarWiring() { return wiring -> wiring.scalar(DATE_TIME).scalar(DATE).scalar(DECIMAL).scalar(JSON); }
}
