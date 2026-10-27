package localapi.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Search that finds substrings and slightly wrong input. Every word of the query must match somewhere.
 * Per word, best of: substring (best), letters in order ("kyd" finds "key/down"), or a word of the text
 * that is 1-2 typos away ("entiti" finds "entities", "invetory" finds "inventory").
 */
public final class Fuzzy {
    private Fuzzy() {}

    /** @return 0 = no match, higher = better. Empty query matches everything with 1. */
    public static int score(String query, String text) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return 1;
        String t = text.toLowerCase(Locale.ROOT);
        String[] words = t.split("[^a-z0-9]+");
        int total = 0;
        for (String tok : q.split("\\s+")) {
            int s = token(tok, t, words);
            if (s == 0) return 0;
            total += s;
        }
        return total;
    }

    private static int token(String tok, String text, String[] words) {
        int idx = text.indexOf(tok);
        if (idx >= 0) {
            int bonus = 0;
            if (idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1))) bonus += 20; // word start
            return 200 + bonus - Math.min(idx, 50) / 5;
        }
        if (tok.length() >= 2 && subsequence(tok, text)) return 60;
        if (tok.length() >= 4) {
            int max = tok.length() >= 7 ? 2 : 1;
            int best = Integer.MAX_VALUE;
            for (String w : words) {
                if (w.length() < 3) continue;
                best = Math.min(best, edit(tok, w, max));
                // also compare against a same-length prefix: "entiti" vs "entities"
                if (w.length() > tok.length()) best = Math.min(best, edit(tok, w.substring(0, tok.length()), max));
            }
            if (best <= max) return 100 - best * 25;
        }
        return 0;
    }

    private static boolean subsequence(String needle, String hay) {
        int i = 0;
        for (int j = 0; j < hay.length() && i < needle.length(); j++) if (hay.charAt(j) == needle.charAt(i)) i++;
        return i == needle.length();
    }

    /** Damerau-Levenshtein distance, early exit above max. */
    private static int edit(String a, String b, int max) {
        if (Math.abs(a.length() - b.length()) > max) return max + 1;
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + c);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1))
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
            }
        }
        return d[a.length()][b.length()];
    }

    /** Filters and sorts best match first. Stable for equal scores. */
    public static <T> List<T> filter(List<T> items, String query, Function<T, String> text) {
        if (query == null || query.isBlank()) return new ArrayList<>(items);
        record Scored<T>(T item, int score, int order) {}
        List<Scored<T>> out = new ArrayList<>();
        int i = 0;
        for (T it : items) {
            int s = score(query, text.apply(it));
            if (s > 0) out.add(new Scored<>(it, s, i));
            i++;
        }
        out.sort((x, y) -> x.score() != y.score() ? Integer.compare(y.score(), x.score()) : Integer.compare(x.order(), y.order()));
        List<T> r = new ArrayList<>();
        for (Scored<T> s : out) r.add(s.item());
        return r;
    }
}
