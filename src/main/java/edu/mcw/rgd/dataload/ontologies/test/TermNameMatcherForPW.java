package edu.mcw.rgd.dataload.ontologies.test;

import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;

import java.util.*;

public class TermNameMatcherForPW {

    // map of normalized term names / synonyms to set of term acc
    Map<String, Set<String>> map = new HashMap<>();

    public void loadTerms(List<Term> terms) {
        for( Term t: terms ) {
            indexName(t.getTerm(), t.getAccId());
            indexName(t.getDefinition(), t.getAccId());
        }
    }

    public void loadSynonyms(List<TermSynonym> synonyms) {
        for( TermSynonym syn: synonyms ) {
            indexName(syn.getName(), syn.getTermAcc());
        }
    }

    public Set<String> getMatches(String name, int hitLimit) {
        String[] tokens = normalizeTerm(name);

        Set<String> results = new HashSet<>();
        for( String token: tokens ) {
            Set<String> hits = map.get(token);
            if( hits!=null && hits.size()<=hitLimit ) {
                results.addAll(hits);
            }
        }
        return results;
    }

    void indexName(String name, String termAcc) {
        String[] tokens = normalizeTerm(name);
        if( tokens==null ) {
            return;
        }
        for( String token: tokens ) {
            Set<String> termAccs = map.get(token);
            if( termAccs==null ) {
                termAccs = new HashSet<>();
                map.put(token, termAccs);
            }
            termAccs.add(termAcc);
        }
    }

    String[] normalizeTerm(String term) {
        // arg validation check
        if( term == null ) {
            return null;
        }

        String[] words = term.replace('-',' ').replace(',',' ').replace('(',' ').replace(')',' ').replace('/',' ')
                .toLowerCase().split("[\\s]");
        return words;
    }
}
