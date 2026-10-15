package ai.intentoms.copilot.change;

import java.util.ArrayList;
import java.util.List;

/** Line-based unified diff (3 lines of context) built from an LCS so reviewers see exactly what a proposal changes. */
public final class UnifiedDiff {
    private UnifiedDiff() {}

    public static String of(String before, String after, String name) {
        String[] a = before == null ? new String[0] : before.split("\n", -1);
        String[] b = after == null ? new String[0] : after.split("\n", -1);
        List<Op> ops = diff(a, b);
        StringBuilder out = new StringBuilder();
        out.append("--- ").append(name).append(before == null ? " (new)" : " (current)").append('\n');
        out.append("+++ ").append(name).append(" (proposed)").append('\n');
        int i = 0;
        while (i < ops.size()) {
            if (ops.get(i).type == ' ') { i++; continue; }
            int start = Math.max(0, i - 3);
            int end = i;
            int quiet = 0;
            while (end < ops.size() && quiet <= 3) {
                if (ops.get(end).type == ' ') quiet++; else quiet = 0;
                end++;
            }
            if (quiet > 3) end -= (quiet - 3);
            int oldStart = ops.get(start).oldLine, newStart = ops.get(start).newLine, oldCount = 0, newCount = 0;
            StringBuilder hunk = new StringBuilder();
            for (int k = start; k < end; k++) {
                Op op = ops.get(k);
                if (op.type != '+') oldCount++;
                if (op.type != '-') newCount++;
                hunk.append(op.type).append(op.text).append('\n');
            }
            out.append("@@ -").append(oldStart).append(',').append(oldCount).append(" +").append(newStart).append(',').append(newCount).append(" @@\n").append(hunk);
            i = end;
        }
        return out.toString();
    }

    /** Count of added/removed lines, for summaries. */
    public static int[] stats(String before, String after) {
        int add = 0, del = 0;
        for (Op op : diff(before == null ? new String[0] : before.split("\n", -1), after == null ? new String[0] : after.split("\n", -1))) {
            if (op.type == '+') add++; else if (op.type == '-') del++;
        }
        return new int[]{add, del};
    }

    private record Op(char type, String text, int oldLine, int newLine) {}

    private static List<Op> diff(String[] a, String[] b) {
        int n = a.length, m = b.length;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--) lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        List<Op> ops = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) { ops.add(new Op(' ', a[i], i + 1, j + 1)); i++; j++; }
            else if (lcs[i + 1][j] >= lcs[i][j + 1]) { ops.add(new Op('-', a[i], i + 1, j + 1)); i++; }
            else { ops.add(new Op('+', b[j], i + 1, j + 1)); j++; }
        }
        while (i < n) { ops.add(new Op('-', a[i], i + 1, j + 1)); i++; }
        while (j < m) { ops.add(new Op('+', b[j], i + 1, j + 1)); j++; }
        return ops;
    }
}
