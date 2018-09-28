package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.Utils;

import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * User: mtutaj
 * Date: 1/29/13
 * Time: 10:39 AM
 * class to handle all incoming synonyms and to remove duplicate synonyms;
 * two synonyms are equal only if their names stripped of whitespace and punctuation are identical
 * for example "Abdominal Injuries", "Injuries, Abdominal" "Abdominal-Injuries" "ABDOMINAL--INJURIES"
 *    are all considered equal, and only one of these synonyms should be inserted into database
 * <p>
 * Internally all synonyms are stored in 'processed' form: they are made lowercase, split into words, words sorted
 * and then concatenated; f.e. 'processed' synonym for "Injuries, Abdominal" is "abdominal.injuries"
 * <p>
 * if parameter 'exactMatchSynonyms' is set to true, this behavior is changed:
 * 'processed' form only makes synonyms lowercase for purpose of uniqueness determination
 */
public class SynonymManager {

    public boolean exactMatchSynonyms = false;

    protected String process(String synonym, String synonymType) {
        if( synonym==null )
            return "";
        String processedName = synonym.toLowerCase();
        if( !exactMatchSynonyms ) {
            String[] words = synonym.toLowerCase().split("\\W");
            Arrays.sort(words);
            processedName = Utils.concatenate(words, ".");
        }
        if( !synonymType.contains("synonym") )
            processedName += "."+synonymType;
        return processedName;
    }

    public Collection<TermSynonym> incomingSynonyms;
    public Collection<TermSynonym> inRgdSynonyms;

    // call qc() to populate 3 lists below
    public Collection<TermSynonym> matchingSynonyms = new ArrayList<>();
    public Collection<TermSynonym> forInsertSynonyms = new ArrayList<>();
    public Collection<TermSynonym> forDeleteSynonyms;

    /**
     * qc
     * @param termName term name (no synonym could have the same name as term name)
     * @param inRgdList list of synonyms in rgd
     * @param incomingList list of incoming synonyms
     */
    public void qc(String termName, List<TermSynonym> inRgdList, List<TermSynonym> incomingList) {

        String processedTermName = process(termName, "synonym");
        incomingSynonyms = incomingList;
        inRgdSynonyms = inRgdList;

        // pass 'inRgdList' through uniqueness pipeline
        // to remove duplicate synonyms
        removeDuplicates(processedTermName, inRgdList);
        forDeleteSynonyms = deleteSynonyms;

        qcIncomingSynonyms(processedTermName, incomingList);
    }


    private Map<String, TermSynonym> processedMap = new HashMap<>();
    private Collection<TermSynonym> deleteSynonyms;

    private void qcIncomingSynonyms(String processedTermName, List<TermSynonym> synonyms) {

        for( TermSynonym syn: synonyms ) {

            // synonym cannot be like term name
            String processedSynonymName = process(syn.getName(), syn.getType());
            if( processedSynonymName.equals(processedTermName) ) {
                matchingSynonyms.add(syn);
                continue;
            }

            TermSynonym synInMap = processedMap.get(processedSynonymName);
            if( synInMap==null ) {
                // new synonym is unique
                processedMap.put(processedSynonymName, syn);
                forInsertSynonyms.add(syn);
                continue;

            }
            // another synonym with same 'processed name' is already in the map
            // only one should be left
            // leave synonym having:
            // 1. longer synonym type ('exact_synonym' preferred from 'synonym')
            // 2. shorter synonym name ('Abdominal Injury' preferred from 'Injury, Abdominal'

            // 1.
            int synInMapLen = synInMap.getType().length();
            int incomingLen = syn.getType().length();
            if( incomingLen > synInMapLen ) {
                // incoming synonym has longer type name -- use it
                forDeleteSynonyms.add(synInMap);
                forInsertSynonyms.add(syn);
                processedMap.put(processedSynonymName, syn);
            }
            else if( incomingLen==synInMapLen ) {
                // same length of synonym type name

                synInMapLen = synInMap.getName().length();
                incomingLen = syn.getName().length();
                if( incomingLen < synInMapLen ) {
                    // incoming synonym has shorter name
                    forDeleteSynonyms.add(synInMap);
                    forInsertSynonyms.add(syn);
                    processedMap.put(processedSynonymName, syn);
                }
                else {
                    matchingSynonyms.add(synInMap);
                }
            }
            else {
                matchingSynonyms.add(synInMap);
            }
        }
    }

    private void removeDuplicates(String processedTermName, List<TermSynonym> synonyms) {

        // hash to store unique 'processed' synonyms
        processedMap = new HashMap<>();

        for( TermSynonym syn: synonyms ) {

            // synonym cannot be like term name
            String processedSynonymName = process(syn.getName(), syn.getType());
            if( processedSynonymName.equals(processedTermName) ) {
                continue;
            }

            TermSynonym synInMap = processedMap.get(processedSynonymName);
            if( synInMap==null ) {
                // new synonym is unique
                processedMap.put(processedSynonymName, syn);
                continue;

            }
            // another synonym with same 'processed name' is already in the map
            // only one should be left
            // leave synonym having:
            // 1. longer synonym type ('exact_synonym' preferred from 'synonym')
            // 2. shorter synonym name ('Abdominal Injury' preferred from 'Injury, Abdominal'

            // 1.
            int synInMapLen = synInMap.getType().length();
            int incomingLen = syn.getType().length();
            if( incomingLen > synInMapLen ) {
                // incoming synonym has longer type name -- use it
                processedMap.put(processedSynonymName, syn);
            }
            else if( incomingLen==synInMapLen ) {
                // same length of synonym type name

                synInMapLen = synInMap.getName().length();
                incomingLen = syn.getName().length();
                if( incomingLen < synInMapLen ) {
                    // incoming synonym has shorter name
                    processedMap.put(processedSynonymName, syn);
                }
            }
        }

        // unique synonym list
        Collection<TermSynonym> uniqueSynonyms = processedMap.values();

        // deleted synonyms
        this.deleteSynonyms = new ArrayList<>(synonyms);
        this.deleteSynonyms.removeAll(uniqueSynonyms);
    }
}
