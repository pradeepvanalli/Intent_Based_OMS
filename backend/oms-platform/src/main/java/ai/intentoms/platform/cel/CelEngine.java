package ai.intentoms.platform.cel;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.CelOptions;
import dev.cel.parser.CelStandardMacro;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.extensions.CelExtensions;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safe, non-Turing-complete expression evaluation for state-machine guards, L0 rules, pre/post checks and
 * decision-table conditions (FR-SM-03, FR-DEC-03). Variables are untyped maps (dyn) so tenant extension fields
 * work without recompilation; static validation checks that every root variable an expression references is
 * declared (FR-SM-10 "guards that reference unknown fields").
 */
@Component
public class CelEngine {
    /** Root variables every guard/rule may reference. Modules bind what they have; missing ones are empty maps. */
    public static final Set<String> STANDARD_ROOTS = Set.of(
        "order", "line", "lines", "plan", "chosen_plan", "plan_options", "tenant", "approval", "customer", "node",
        "event", "decision", "answers", "ctx", "return", "payment", "agent", "mandate", "shipment", "exception", "task", "input", "state", "now"
    );

    private static final Pattern IDENT = Pattern.compile("(?<![\\w.'\"])([A-Za-z_][A-Za-z0-9_]*)");
    private static final Set<String> KEYWORDS = Set.of("true", "false", "null", "in", "has", "size", "all", "exists", "exists_one", "map", "filter",
        "matches", "startsWith", "endsWith", "contains", "int", "uint", "double", "string", "bytes", "bool", "timestamp", "duration", "type", "dyn", "list");

    private final CelCompiler compiler;
    private final CelRuntime runtime;
    private final Cache<String, CelRuntime.Program> programs = Caffeine.newBuilder().maximumSize(10_000).build();

    public CelEngine() {
        CelOptions options = CelOptions.current().enableHeterogeneousNumericComparisons(true).build();
        var cb = CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(options)
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addLibraries(CelExtensions.strings(), CelExtensions.math(), CelExtensions.lists())
            .setResultType(SimpleType.DYN);
        for (String root : STANDARD_ROOTS) {
            if (root.equals("lines") || root.equals("plan_options")) cb.addVar(root, ListType.create(SimpleType.DYN));
            else if (root.equals("now")) cb.addVar(root, SimpleType.TIMESTAMP);
            else cb.addVar(root, MapType.create(SimpleType.STRING, SimpleType.DYN));
        }
        this.compiler = cb.build();
        this.runtime = CelRuntimeFactory.standardCelRuntimeBuilder()
            .setOptions(options)
            .addLibraries(CelExtensions.strings(), CelExtensions.math(), CelExtensions.lists())
            .build();
    }

    /** Compile-time validation. Returns an empty list when the expression is valid. */
    public List<String> validate(String expression) {
        List<String> issues = new ArrayList<>();
        try {
            CelValidationResult r = compiler.compile(expression);
            if (r.hasError()) issues.add(r.getErrorString());
        } catch (Exception e) {
            issues.add(e.getMessage());
        }
        return issues;
    }

    /** Root variable names referenced by an expression (best-effort lexical scan; used by static checks). */
    public Set<String> referencedRoots(String expression) {
        Set<String> out = new java.util.LinkedHashSet<>();
        Matcher m = IDENT.matcher(expression);
        while (m.find()) {
            String id = m.group(1);
            if (!KEYWORDS.contains(id)) out.add(id);
        }
        return out;
    }

    public Object eval(String expression, Map<String, Object> vars) {
        try {
            CelRuntime.Program program = programs.get(expression, this::compile);
            Map<String, Object> bound = new java.util.HashMap<>();
            for (String root : STANDARD_ROOTS) {
                Object v = vars.get(root);
                if (v == null) v = root.equals("lines") || root.equals("plan_options") ? List.of() : root.equals("now") ? java.time.Instant.now() : Map.of();
                bound.put(root, v);
            }
            return program.eval(bound);
        } catch (CelEvaluationException e) {
            throw new CelException("Evaluation failed for '" + expression + "': " + e.getMessage(), e);
        }
    }

    public boolean evalBool(String expression, Map<String, Object> vars) {
        Object v = eval(expression, vars);
        if (v instanceof Boolean b) return b;
        throw new CelException("Expression '" + expression + "' did not return a boolean but " + v);
    }

    private CelRuntime.Program compile(String expression) {
        try {
            CelAbstractSyntaxTree ast = compiler.compile(expression).getAst();
            return runtime.createProgram(ast);
        } catch (CelValidationException | CelEvaluationException e) {
            throw new CelException("Invalid CEL '" + expression + "': " + e.getMessage(), e);
        }
    }

    public static class CelException extends RuntimeException {
        public CelException(String m, Throwable c) { super(m, c); }
        public CelException(String m) { super(m); }
    }
}
