package edu.mcw.rgd.dataload.ontologies.test;

import edu.mcw.rgd.dao.impl.OntologyXDAO;
import edu.mcw.rgd.dataload.ontologies.OntologyDAO;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.Utils;

import java.io.BufferedWriter;
import java.util.*;

/**
 * Maps OBA (Ontology of Biological Attributes) terms to other RGD ontologies by term-name matching,
 * producing a curator-review report (no database changes).
 *
 * <p>Input set (for testing): the OBA ids used as trait ids in the GWAS catalog
 * ({@code GWAS_CATALOG.efo_ids}); the {@code OBA:VT...} ids are excluded because they carry a VT id
 * directly ({@code OBA:VT0001253 -> VT:0001253}) and need no mapping.
 *
 * <p>Targets are the trait / clinical-measurement ontologies {@link #TARGET_ONTS} (VT, CMO), the only
 * ontologies where OBA (biological attributes) matches meaningfully; matching against every ontology
 * was measured to be almost entirely false positives (chemicals, cell lines, ...).
 *
 * <p>Each OBA term is matched in two passes and the winning match is flagged:
 * <ol>
 *   <li><b>EXACT</b> - the OBA term name or an exact synonym normalizes (order-insensitive, punctuation
 *       stripped) to a target term name or synonym ({@link TermNameMatcher}). High confidence.</li>
 *   <li><b>PARTIAL</b> - only when there is no exact match: the most specific (largest-token) target whose
 *       normalized tokens are all contained in the OBA term name. Lower confidence; typically a coarse
 *       parent bucket, e.g. "level of protein Wnt-10b in blood serum" ~> VT "blood protein amount".</li>
 * </ol>
 */
public class OBAMapper {

    static OntologyXDAO odao = new OntologyXDAO();
    static OntologyDAO dao = new OntologyDAO();

    // ontologies OBA is mapped against (trait + clinical measurement)
    static final String[] TARGET_ONTS = {"VT", "CMO"};

    // synonym types considered a term's alternative names (for both OBA and the targets)
    static final String[] NAME_SYNONYM_TYPES = {"exact_synonym", "broad_synonym", "narrow_synonym", "related_synonym"};

    // a target term name/synonym reduced to its token set, for partial (subset) matching
    static final class Target {
        final String acc;
        final String text;         // the original name/synonym these tokens came from
        final Set<String> tokens;
        Target(String acc, String text, Set<String> tokens) { this.acc = acc; this.text = text; this.tokens = tokens; }
    }

    public static void main(String[] args) throws Exception {
        run();
    }

    static void run() throws Exception {

        // --- build the matchers over the target ontologies (VT, CMO) ---
        TermNameMatcher exactMatcher = new TermNameMatcher();     // normalized-exact name matching
        Map<String, String> targetName = new HashMap<>();          // target acc -> its primary term name
        Map<String, String> targetText = new HashMap<>();          // "acc|normalized" -> the original name/synonym that matched
        List<Target> partialTargets = new ArrayList<>();           // target name/synonym token sets (>=2 tokens)

        for (String ontId : TARGET_ONTS) {
            List<Term> terms = odao.getActiveTerms(ontId);
            exactMatcher.loadTerms(terms);
            for (Term t : terms) {
                targetName.put(t.getAccId(), t.getTerm());
                targetText.putIfAbsent(t.getAccId() + "|" + exactMatcher.normalizeTerm(t.getTerm()), t.getTerm());
                addPartialTarget(partialTargets, t.getAccId(), t.getTerm());
            }
            for (String synType : NAME_SYNONYM_TYPES) {
                List<TermSynonym> syns = dao.getActiveSynonymsByType(ontId, synType);
                exactMatcher.loadSynonyms(syns);
                for (TermSynonym syn : syns) {
                    targetText.putIfAbsent(syn.getTermAcc() + "|" + exactMatcher.normalizeTerm(syn.getName()), syn.getName());
                    addPartialTarget(partialTargets, syn.getTermAcc(), syn.getName());
                }
            }
        }
        // index partial targets by their rarest token, so an OBA term only probes plausible candidates.
        // This is exhaustive: a subset target's rarest token is one of its tokens, hence in the OBA token set.
        Map<String, Integer> tokenFreq = new HashMap<>();
        for (Target tg : partialTargets) {
            for (String tok : tg.tokens) tokenFreq.merge(tok, 1, Integer::sum);
        }
        Map<String, List<Target>> targetsByRareToken = new HashMap<>();
        for (Target tg : partialTargets) {
            String rare = null;
            int best = Integer.MAX_VALUE;
            for (String tok : tg.tokens) {
                int f = tokenFreq.get(tok);
                if (f < best) { best = f; rare = tok; }
            }
            targetsByRareToken.computeIfAbsent(rare, k -> new ArrayList<>()).add(tg);
        }
        System.out.println("target terms indexed for " + Arrays.toString(TARGET_ONTS)
                + ": " + targetName.size() + " terms, " + partialTargets.size() + " name/synonym token sets");

        // --- load the OBA terms (names + exact synonyms) once, keyed by accession ---
        Map<String, String> obaName = new HashMap<>();
        Map<String, Set<String>> obaStrings = new HashMap<>();   // acc -> {term name, exact synonyms}
        for (Term t : odao.getActiveTerms("OBA")) {
            obaName.put(t.getAccId(), t.getTerm());
            obaStrings.computeIfAbsent(t.getAccId(), k -> new HashSet<>()).add(t.getTerm());
        }
        for (TermSynonym syn : dao.getActiveSynonymsByType("OBA", "exact_synonym")) {
            if (obaStrings.containsKey(syn.getTermAcc()) && syn.getName() != null) {
                obaStrings.get(syn.getTermAcc()).add(syn.getName());
            }
        }

        // --- the test input set: OBA ids used in the GWAS catalog (OBA:VT... excluded) ---
        List<String> obaIds = new ArrayList<>(dao.getObaIdsFromGWAS());
        Collections.sort(obaIds);
        System.out.println("OBA ids from GWAS catalog to map: " + obaIds.size());

        // --- match and report ---
        BufferedWriter out = Utils.openWriter("OBA_to_" + String.join("_", TARGET_ONTS) + "_mappings.txt");
        final String TAB = "\t";

        List<String> exactRows = new ArrayList<>();
        List<String> partialRows = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        Map<String, Integer> exactByOnt = new TreeMap<>();
        Map<String, Integer> partialByOnt = new TreeMap<>();
        int notFound = 0;

        for (String obaId : obaIds) {
            Set<String> strings = obaStrings.get(obaId);
            if (strings == null) {   // OBA id from GWAS not active in RGD (defensive; expected to be 0)
                notFound++;
                continue;
            }
            String name = obaName.getOrDefault(obaId, "");

            // pass 1: EXACT - record every distinct target hit, and which target text it matched
            // (matching includes narrow/broad synonyms, so the matched text often differs from the
            //  target's primary name, e.g. OBA "putamen volume" = a narrow synonym of CMO "brain weight")
            Map<String, String> exactMatched = new TreeMap<>();   // target acc -> matched target text
            for (String s : strings) {
                Set<String> hits = exactMatcher.getMatches(s);
                if (hits == null) continue;
                String key = "|" + exactMatcher.normalizeTerm(s);
                for (String tacc : hits) {
                    exactMatched.putIfAbsent(tacc, targetText.getOrDefault(tacc + key, s));
                }
            }
            if (!exactMatched.isEmpty()) {
                for (Map.Entry<String, String> en : exactMatched.entrySet()) {
                    String tacc = en.getKey();
                    String ont = ontOf(tacc);
                    exactRows.add(obaId + TAB + name + TAB + "EXACT" + TAB + ont + TAB + tacc + TAB
                            + targetName.getOrDefault(tacc, "?") + TAB + en.getValue());
                    exactByOnt.merge(ont, 1, Integer::sum);
                }
                continue;
            }

            // pass 2: PARTIAL - most specific target whose tokens are all contained in the OBA name/synonyms
            Set<String> obaTokens = new HashSet<>();
            for (String s : strings) obaTokens.addAll(tokenize(s));
            // each target sits in exactly one rare-token bucket, so it is probed at most once here
            Target bestTarget = null;
            for (String tok : obaTokens) {
                List<Target> cands = targetsByRareToken.get(tok);
                if (cands == null) continue;
                for (Target tg : cands) {
                    if (obaTokens.containsAll(tg.tokens)
                            && (bestTarget == null || tg.tokens.size() > bestTarget.tokens.size())) {
                        bestTarget = tg;
                    }
                }
            }
            if (bestTarget != null) {
                String ont = ontOf(bestTarget.acc);
                partialRows.add(obaId + TAB + name + TAB + "PARTIAL" + TAB + ont + TAB + bestTarget.acc + TAB
                        + targetName.getOrDefault(bestTarget.acc, "?") + TAB + bestTarget.text);
                partialByOnt.merge(ont, 1, Integer::sum);
            } else {
                unmapped.add(obaId + TAB + name);
            }
        }

        // --- summary (also echoed to stdout) ---
        int exactTerms = countDistinctOba(exactRows);
        int partialTerms = partialRows.size();
        List<String> summary = new ArrayList<>();
        summary.add("# OBA -> " + String.join("+", TARGET_ONTS) + " mapping report (source: GWAS_CATALOG.efo_ids, OBA:VT excluded)");
        summary.add("# OBA ids to map           : " + obaIds.size());
        summary.add("# EXACT-matched OBA terms  : " + exactTerms + "  by ontology " + exactByOnt + "  (" + exactRows.size() + " rows)");
        summary.add("# PARTIAL-matched OBA terms: " + partialTerms + "  by ontology " + partialByOnt);
        summary.add("# UNMAPPED OBA terms       : " + unmapped.size());
        if (notFound > 0) summary.add("# not active in RGD        : " + notFound);
        for (String line : summary) System.out.println(line);

        for (String line : summary) out.write(line + "\n");
        out.write("\nOBA ID\tOBA term\tmatch\ttarget ont\ttarget ID\ttarget term\tmatched on\n");
        for (String r : exactRows) out.write(r + "\n");
        for (String r : partialRows) out.write(r + "\n");
        out.write("\n\nUNMAPPED OBA TERMS (" + unmapped.size() + ")\n====================\n");
        for (String r : unmapped) out.write(r + "\n");
        out.close();

        System.out.println("report written: OBA_to_" + String.join("_", TARGET_ONTS) + "_mappings.txt");
    }

    /** add a target name/synonym as a partial-match candidate (token set), skipping single-token names. */
    static void addPartialTarget(List<Target> targets, String acc, String name) {
        Set<String> tokens = tokenize(name);
        if (tokens.size() >= 2) {
            targets.add(new Target(acc, name, tokens));
        }
    }

    /** split a term name into a lowercase token set, the same way {@link TermNameMatcher} splits (minus the sort). */
    static Set<String> tokenize(String name) {
        Set<String> tokens = new HashSet<>();
        if (name == null) {
            return tokens;
        }
        for (String w : name.replace('-', ' ').replace(',', ' ').replace('(', ' ').replace(')', ' ').replace('/', ' ')
                .toLowerCase().split("\\s+")) {
            if (!w.isBlank()) tokens.add(w);
        }
        return tokens;
    }

    /** ontology id from an accession, e.g. "VT:0001234" -> "VT". */
    static String ontOf(String acc) {
        int c = acc.indexOf(':');
        return c < 0 ? acc : acc.substring(0, c);
    }

    static int countDistinctOba(List<String> rows) {
        Set<String> ids = new HashSet<>();
        for (String r : rows) ids.add(r.substring(0, r.indexOf('\t')));
        return ids.size();
    }
}
