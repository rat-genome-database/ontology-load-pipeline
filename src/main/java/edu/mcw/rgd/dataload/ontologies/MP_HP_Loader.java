package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.CounterPool;
import edu.mcw.rgd.process.FileDownloader;
import edu.mcw.rgd.process.Utils;
import org.apache.commons.collections4.CollectionUtils;

import java.io.BufferedReader;
import java.text.SimpleDateFormat;
import java.util.*;

public class MP_HP_Loader {

    public static String SOURCE = "MP2HP";

    public static void main( String[] args ) throws Exception {

        int col_object_id = -1;
        int col_object_label = -1;
        int col_predicate_id = -1;
        int col_confidence = -1;
        int col_subject_id = -1;
        int col_subject_label = -1;
        int col_mapping_justification = -1;
        int col_author_id = -1;
        int col_mapping_date = -1;
        int col_comment = -1;
        int col_other = -1;

        String url = "https://raw.githubusercontent.com/mapping-commons/mh_mapping_initiative/master/mappings/mp_hp_mgi_all.sssom.tsv";

        FileDownloader fd = new FileDownloader();
        fd.setExternalFile(url);
        fd.setLocalFile("data/mp_hp_mgi_all.sssom.tsv");
        String localFile = fd.downloadNew();

        // 1st two data lines
        //object_id	object_label	predicate_id	confidence	subject_id	subject_label	mapping_justification	author_id	mapping_date	comment	other
        //HP:0000016	Urinary retention	skos:exactMatch	1	MP:0003622	ischuria	semapv:ManualMappingCuration	orcid:0000-0003-4606-0597	2022-08-02	scoliosis

        String[] fields = null;
        BufferedReader in = Utils.openReader(localFile);
        String line;
        while( (line=in.readLine())!=null ) {
            // skip lines starting with '#'
            if( line.startsWith("#") ) {
                continue;
            }
            if( fields==null ) {
                fields = line.split("[\\t]", -1);

                for( int i=0; i<fields.length; i++ ) {
                    switch( fields[i] ) {
                        case "object_id": col_object_id = i; break;
                        case "object_label": col_object_label = i; break;
                        case "predicate_id": col_predicate_id = i; break;
                        case "confidence": col_confidence = i; break;
                        case "subject_id": col_subject_id = i; break;
                        case "subject_label": col_subject_label = i; break;
                        case "mapping_justification": col_mapping_justification = i; break;
                        case "author_id": col_author_id = i; break;
                        case "mapping_date": col_mapping_date = i; break;
                        case "comment": col_comment = i; break;
                        case "other": col_other = i; break;
                        default: System.out.println("WARNING! unexpected column: "+fields[i]);
                    }
                }

                break;
            }
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        CounterPool counters = new CounterPool();

        // process data lines

        Map<String, Set<TermSynonym>> incomingSynonyms = new HashMap<>();

        while( (line=in.readLine())!=null ) {

            counters.increment("MAPPINGS_PROCESSED");

            String[] cols = line.split("[\\t]", -1);
            String mpTermAcc = cols[col_subject_id];
            String hpTermAcc = cols[col_object_id];
            Date mappingDate = sdf.parse( cols[col_mapping_date] );

            Set<TermSynonym> incomingList = incomingSynonyms.get(mpTermAcc);
            if( incomingList==null ) {
                incomingList = new HashSet<>();
                incomingSynonyms.put(mpTermAcc, incomingList);
            }

            TermSynonym xrefIncoming = new TermSynonym();
            xrefIncoming.setTermAcc( mpTermAcc );
            xrefIncoming.setCreatedDate(mappingDate);
            xrefIncoming.setType("xref");
            xrefIncoming.setSource(SOURCE);
            xrefIncoming.setName( hpTermAcc );
            incomingList.add(xrefIncoming);

            TermSynonym synIncoming = new TermSynonym();
            synIncoming.setTermAcc( mpTermAcc );
            synIncoming.setCreatedDate(mappingDate);
            synIncoming.setType( getSynonymType(cols[col_predicate_id]) );
            synIncoming.setSource(SOURCE);
            synIncoming.setName( cols[col_object_label] );
            synIncoming.setDbXrefs( hpTermAcc );
            incomingList.add(synIncoming);
        }

        in.close();

        qcAndLoad( counters, incomingSynonyms );
    }

    static void qcAndLoad( CounterPool counters, Map<String, Set<TermSynonym>> incomingSynonyms ) throws Exception {

        Date dateStart = new Date();

        OntologyDAO dao = new OntologyDAO();

        incomingSynonyms.entrySet().stream().forEach( entry -> {

            String mpTermAcc = entry.getKey();
            Set<TermSynonym> synonyms = entry.getValue();
            List<TermSynonym> synonymsInRgd = null;

            Collection<TermSynonym> matchingSynonyms = null;
            Collection<TermSynonym> toBeInsertedSynonyms = null;

            try {
                // some terms could be obsolete: replace them with equivalents
                Term mpTerm = dao.getTerm(mpTermAcc);
                if( mpTerm==null || mpTerm.isObsolete() ) {
                    List<Term> terms = dao.getTermsBySynonym("MP", mpTermAcc);
                    terms.removeIf( t -> t.isObsolete() );
                    if( terms.isEmpty() ) {
                        counters.increment("WARNING! TERM NOT IN RGD: "+mpTermAcc);
                        return;
                    }
                    if( terms.size()>1 ) {
                        counters.increment("WARNING! MULTIPLE TERMS MATCHING OBSOLETE TERM: "+mpTermAcc);
                        return;
                    }

                    Term newTerm = terms.get(0);
                    counters.increment("WARNING! OBSOLETE TERM ["+mpTermAcc+"] replaced with active term ["+newTerm.getAccId()+"]");

                    mpTermAcc = newTerm.getAccId();
                    for( TermSynonym tsyn: synonyms ) {
                        tsyn.setTermAcc(mpTermAcc);
                    }
                }

                synonymsInRgd = dao.getTermSynonyms(mpTermAcc);

                matchingSynonyms = CollectionUtils.intersection(synonymsInRgd, synonyms);
                toBeInsertedSynonyms = CollectionUtils.subtract(synonyms, synonymsInRgd);

                // update last modified date for term synonyms created by this pipeline
                if( !matchingSynonyms.isEmpty() ) {

                    List<TermSynonym> matchingSynonyms2 = new ArrayList<>(matchingSynonyms);
                    matchingSynonyms2.removeIf( tsyn -> !tsyn.getSource().equals(SOURCE) );

                    if( !matchingSynonyms2.isEmpty() ) {
                        dao.updateTermSynonymLastModifiedDate(matchingSynonyms2);
                        counters.add("SYNONYMS_LASTMODIFIEDDATE_UPDATED", matchingSynonyms2.size());
                    }

                    counters.add("SYNONYMS_MATCHING_SOURCE_OTHER_THAN_"+SOURCE, matchingSynonyms.size() - matchingSynonyms2.size());
                }

                for( TermSynonym tsyn: toBeInsertedSynonyms ) {
                    dao.insertTermSynonym(tsyn, SOURCE);
                    counters.increment("SYNONYMS_INSERTED");
                }

            } catch( Exception e ) {
                throw new RuntimeException(e);
            }

        });


        List<TermSynonym> obsoleteTermSynonyms = dao.getTermSynonymsModifiedBefore("MP", SOURCE, dateStart );
        if( !obsoleteTermSynonyms.isEmpty() ) {
            dao.deleteTermSynonyms(obsoleteTermSynonyms);
            counters.add("SYNONYMS_DELETED", obsoleteTermSynonyms.size());
        }

        System.out.println(counters.dumpAlphabetically());
    }

    static String getSynonymType( String predicateId ) {
        return switch (predicateId) {
            case "skos:exactMatch" -> "exact_synonym";
            case "skos:narrowMatch" -> "narrow_synonym";
            case "skos:broadMatch" -> "broad_synonym";
            case "skos:relatedMatch" -> "related_synonym";
            case "skos:closeMatch" -> "related_synonym";
            default -> "synonym";
        };
    }

    class SssomInfo {
        public String object_id;
        public String object_label;
        public String predicate_id;
        public String confidence;
        public String subject_id;
        public String subject_label;
        public String mapping_justification;
        public String author_id;
        public String mapping_date;
        public String comment;
        public String other;
    }
}
