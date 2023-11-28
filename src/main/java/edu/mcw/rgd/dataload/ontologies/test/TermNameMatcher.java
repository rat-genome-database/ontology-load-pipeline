package edu.mcw.rgd.dataload.ontologies.test;

import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.Utils;

import java.util.*;

public class TermNameMatcher {

    // map of normalized term names / synonyms to set of term acc
    Map<String, Set<String>> map = new HashMap<>();

    public void loadTerms(List<Term> terms) {
        for( Term t: terms ) {
            addName(t.getTerm(), t.getAccId());
        }
    }

    public void loadSynonyms(List<TermSynonym> synonyms) {
        for( TermSynonym syn: synonyms ) {
            addName(syn.getName(), syn.getTermAcc());
        }
    }

    public void loadTerms(List<Term> terms, String excludedSuffix) {
        for( Term t: terms ) {
            String termName = t.getTerm().endsWith(excludedSuffix) ?
                    t.getTerm().substring(0, t.getTerm().length() - excludedSuffix.length()) :
                    t.getTerm();
            addName(termName, t.getAccId());
        }
    }

    public void loadSynonyms(List<TermSynonym> synonyms, String excludedSuffix) {
        for( TermSynonym syn: synonyms ) {
            String synName = syn.getName().endsWith(excludedSuffix) ?
                    syn.getName().substring(0, syn.getName().length() - excludedSuffix.length()) :
                    syn.getName();
            addName(synName, syn.getTermAcc());
        }
    }

    public Set<String> getMatches(String name) {
        String normalizedName = normalizeTerm(name);
        return map.get(normalizedName);
    }

    void addName(String name, String termAcc) {
        String normalizedName = normalizeTerm(name);
        Set<String> termAccs = map.get(normalizedName);
        if( termAccs==null ) {
            termAccs = new HashSet<>();
            map.put(normalizedName, termAccs);
        }
        termAccs.add(termAcc);
    }

    String normalizeTerm(String term) {
        // arg validation check
        if( term == null ) {
            return "";
        }

        // special handling for terms RDO:0012607 and RDO:0012696, that are falsely reported as duplicates
        if( term.contains("T Cell-") && term.contains("B Cell-") && term.contains("NK Cell-") ) {
            term = term.replace("T Cell-","TCell").replace("B Cell-","BCell").replace("NK Cell-","NKCell");
        }

        String[] words = term.replace('-',' ').replace(',',' ').replace('(',' ').replace(')',' ').replace('/',' ')
                .toLowerCase().split("[\\s]");
        Arrays.sort(words);
        return Utils.concatenate(Arrays.asList(words), ".");
    }
}
