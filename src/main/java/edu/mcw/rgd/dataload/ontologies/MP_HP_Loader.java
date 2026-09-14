package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.CounterPool;
import edu.mcw.rgd.process.FileDownloader2;
import edu.mcw.rgd.process.Utils;
import org.apache.commons.collections4.CollectionUtils;

import java.io.BufferedReader;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * loads MP-HP mappings from the MGI SSSOM mapping file: every mapping gets an 'xref' synonym and a typed
 * label synonym on the MP term, and a reciprocal 'xref' synonym on the HP term, so the Ontology Browser
 * links the mapping from either side; properties are configured in the 'mpHpLoader' bean in AppConfigure.xml
 */
public class MP_HP_Loader {

    private String source;
    private String sssomFileUrl;
    private String localFile;
    private Map<String,String> synonymTypes;

    public void run() throws Exception {

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

        FileDownloader2 fd = new FileDownloader2();
        fd.setExternalFile(getSssomFileUrl());
        fd.setLocalFile(getLocalFile());
        String downloadedFile = fd.downloadNew();

        // 1st two data lines
        //object_id	object_label	predicate_id	confidence	subject_id	subject_label	mapping_justification	author_id	mapping_date	comment	other
        //HP:0000016	Urinary retention	skos:exactMatch	1	MP:0003622	ischuria	semapv:ManualMappingCuration	orcid:0000-0003-4606-0597	2022-08-02	scoliosis

        String[] fields = null;
        BufferedReader in = Utils.openReader(downloadedFile);
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

        Map<String, Set<TermSynonym>> incomingMpSynonyms = new HashMap<>();
        Map<String, Set<TermSynonym>> incomingHpSynonyms = new HashMap<>();

        while( (line=in.readLine())!=null ) {

            counters.increment("MAPPINGS_PROCESSED");

            String[] cols = line.split("[\\t]", -1);
            String mpTermAcc = cols[col_subject_id];
            String hpTermAcc = cols[col_object_id];
            Date mappingDate = sdf.parse( cols[col_mapping_date] );

            Set<TermSynonym> incomingList = incomingMpSynonyms.get(mpTermAcc);
            if( incomingList==null ) {
                incomingList = new HashSet<>();
                incomingMpSynonyms.put(mpTermAcc, incomingList);
            }

            TermSynonym xrefIncoming = new TermSynonym();
            xrefIncoming.setTermAcc( mpTermAcc );
            xrefIncoming.setCreatedDate(mappingDate);
            xrefIncoming.setType("xref");
            xrefIncoming.setSource(getSource());
            xrefIncoming.setName( hpTermAcc );
            incomingList.add(xrefIncoming);

            TermSynonym synIncoming = new TermSynonym();
            synIncoming.setTermAcc( mpTermAcc );
            synIncoming.setCreatedDate(mappingDate);
            synIncoming.setType( getSynonymType(cols[col_predicate_id], counters) );
            synIncoming.setSource(getSource());
            synIncoming.setName( cols[col_object_label] );
            synIncoming.setDbXrefs( hpTermAcc );
            incomingList.add(synIncoming);

            // reciprocal xref on the HP term, so users can navigate the mapping from either side
            Set<TermSynonym> incomingHpList = incomingHpSynonyms.get(hpTermAcc);
            if( incomingHpList==null ) {
                incomingHpList = new HashSet<>();
                incomingHpSynonyms.put(hpTermAcc, incomingHpList);
            }

            TermSynonym xrefReciprocal = new TermSynonym();
            xrefReciprocal.setTermAcc( hpTermAcc );
            xrefReciprocal.setCreatedDate(mappingDate);
            xrefReciprocal.setType("xref");
            xrefReciprocal.setSource(getSource());
            xrefReciprocal.setName( mpTermAcc );
            incomingHpList.add(xrefReciprocal);
        }

        in.close();

        qcAndLoad( counters, incomingMpSynonyms, "MP" );
        qcAndLoad( counters, incomingHpSynonyms, "HP" );

        System.out.println(counters.dumpAlphabetically());
    }

    void qcAndLoad( CounterPool counters, Map<String, Set<TermSynonym>> incomingSynonyms, String ontId ) throws Exception {

        Date dateStart = new Date();

        OntologyDAO dao = new OntologyDAO();

        incomingSynonyms.entrySet().stream().forEach( entry -> {

            String termAcc = entry.getKey();
            Set<TermSynonym> synonyms = entry.getValue();
            List<TermSynonym> synonymsInRgd = null;

            Collection<TermSynonym> matchingSynonyms = null;
            Collection<TermSynonym> toBeInsertedSynonyms = null;

            try {
                // some terms could be obsolete: replace them with equivalents
                Term term = dao.getTerm(termAcc);
                if( term==null || term.isObsolete() ) {
                    List<Term> terms = dao.getTermsBySynonym(ontId, termAcc);
                    terms.removeIf( t -> t.isObsolete() );
                    if( terms.isEmpty() ) {
                        counters.increment("WARNING! TERM NOT IN RGD: "+termAcc);
                        return;
                    }
                    if( terms.size()>1 ) {
                        counters.increment("WARNING! MULTIPLE TERMS MATCHING OBSOLETE TERM: "+termAcc);
                        return;
                    }

                    Term newTerm = terms.get(0);
                    counters.increment("WARNING! OBSOLETE TERM ["+termAcc+"] replaced with active term ["+newTerm.getAccId()+"]");

                    termAcc = newTerm.getAccId();
                    for( TermSynonym tsyn: synonyms ) {
                        tsyn.setTermAcc(termAcc);
                    }
                }

                synonymsInRgd = dao.getTermSynonyms(termAcc);

                matchingSynonyms = CollectionUtils.intersection(synonymsInRgd, synonyms);
                toBeInsertedSynonyms = CollectionUtils.subtract(synonyms, synonymsInRgd);

                // update last modified date for term synonyms created by this pipeline
                if( !matchingSynonyms.isEmpty() ) {

                    List<TermSynonym> matchingSynonyms2 = new ArrayList<>(matchingSynonyms);
                    matchingSynonyms2.removeIf( tsyn -> !tsyn.getSource().equals(getSource()) );

                    if( !matchingSynonyms2.isEmpty() ) {
                        dao.updateTermSynonymLastModifiedDate(matchingSynonyms2);
                        counters.add(ontId+"_SYNONYMS_LASTMODIFIEDDATE_UPDATED", matchingSynonyms2.size());
                    }

                    counters.add(ontId+"_SYNONYMS_MATCHING_SOURCE_OTHER_THAN_"+getSource(), matchingSynonyms.size() - matchingSynonyms2.size());
                }

                for( TermSynonym tsyn: toBeInsertedSynonyms ) {
                    dao.insertTermSynonym(tsyn, getSource());
                    counters.increment(ontId+"_SYNONYMS_INSERTED");
                }

            } catch( Exception e ) {
                throw new RuntimeException(e);
            }

        });


        List<TermSynonym> obsoleteTermSynonyms = dao.getTermSynonymsModifiedBefore(ontId, getSource(), dateStart );
        if( !obsoleteTermSynonyms.isEmpty() ) {
            dao.deleteTermSynonyms(obsoleteTermSynonyms);
            counters.add(ontId+"_SYNONYMS_DELETED", obsoleteTermSynonyms.size());
        }
    }

    /// map an SSSOM predicate to an RGD synonym type, per the 'synonymTypes' bean property;
    /// a predicate missing from the map falls back to the scopeless type 'synonym' -- and is reported,
    /// so that new upstream predicates (f.e. the semapv cross-species family) do not degrade silently
    String getSynonymType( String predicateId, CounterPool counters ) {
        String synonymType = getSynonymTypes().get(predicateId);
        if( synonymType==null ) {
            counters.increment("WARNING! UNEXPECTED PREDICATE (mapped to scopeless 'synonym'): "+predicateId);
            return "synonym";
        }
        return synonymType;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSssomFileUrl() {
        return sssomFileUrl;
    }

    public void setSssomFileUrl(String sssomFileUrl) {
        this.sssomFileUrl = sssomFileUrl;
    }

    public String getLocalFile() {
        return localFile;
    }

    public void setLocalFile(String localFile) {
        this.localFile = localFile;
    }

    public Map<String,String> getSynonymTypes() {
        return synonymTypes;
    }

    public void setSynonymTypes(Map<String,String> synonymTypes) {
        this.synonymTypes = synonymTypes;
    }
}
