package edu.mcw.rgd.dataload.ontologies.test;

import edu.mcw.rgd.dataload.ontologies.OntologyDAO;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.Utils;
import org.apache.commons.collections4.CollectionUtils;

import java.io.BufferedWriter;
import java.util.*;

public class EfoXrefCreator {

    private static final String SOURCE = "RGDSYNC";
    private OntologyDAO dao = new OntologyDAO();

    private int totalXrefsInserted = 0;
    private int totalXrefsDeleted = 0;
    private int totalXrefsUpToDate = 0;

    public static void main(String[] args) throws Exception {

        try {
            new EfoXrefCreator().run();
        } catch( Exception e ) {
            e.printStackTrace();
        }

    }

    public void run() throws Exception {

        run( "CMO", "CMO:%");
        run( "MP", "MP:%");
        run( "HP", "HP:%");
        run( "VT", "VT:%");
        run( "RDO", "DOID:%");

        System.out.println(" === ");
        System.out.println(" TOTAL XREFS INSERTED:   "+totalXrefsInserted);
        System.out.println(" TOTAL XREFS DELETED:    "+totalXrefsDeleted);
        System.out.println(" TOTAL XREFS UP_TO_DATE: "+totalXrefsUpToDate);
    }

    void run( String ontId, String ontPrefixId ) throws Exception {

        System.out.println(" === ");

        List<TermSynonym> efoSynonyms = getEfoSynonyms(ontPrefixId);
        System.out.println(" loaded EFO xrefs for EFO ontology: "+efoSynonyms.size());

        List<TermSynonym> nonEfoSynonyms = dao.getActiveSynonymsByNamePattern(ontId, "EFO:%");
        System.out.println(" loaded EFO xrefs for "+ontId+" ontologies: "+nonEfoSynonyms.size());

        qcAndSync(efoSynonyms, nonEfoSynonyms);

        System.out.println(" === ");

        efoSynonyms = getEfoSynonyms(ontPrefixId);
        System.out.println(" loaded EFO xrefs for EFO ontology: "+efoSynonyms.size());

        nonEfoSynonyms = dao.getActiveSynonymsByNamePattern(ontId, "EFO:%");
        System.out.println(" loaded EFO xrefs for "+ontId+" ontologies: "+nonEfoSynonyms.size());

        qcAndSync(nonEfoSynonyms, efoSynonyms);
    }

    List<TermSynonym> getEfoSynonyms( String ontPrefixId ) throws Exception {

        List<TermSynonym> efoSynonyms = dao.getActiveSynonymsByNamePattern("EFO", ontPrefixId);

        // filter out EFO terms that refer to other ontologies, like 'EFO:MONDO:0000111'
        List<TermSynonym> efoSynonymsFiltered = new ArrayList<>();
        for( TermSynonym termSynonym: efoSynonyms ) {
            String acc = termSynonym.getTermAcc();
            int posColonFirst = acc.indexOf(':');
            int posColonLast = acc.lastIndexOf(':');
            if( posColonFirst <= 0 ) {
                continue;
            }
            if( posColonLast > posColonFirst ) {
                continue;
            }
            efoSynonymsFiltered.add(termSynonym);
        }

        return efoSynonymsFiltered;
    }

    void qcAndSync( Collection<TermSynonym> synonyms1, Collection<TermSynonym> synonyms2 ) throws Exception {

        Set<TermSynonym> inRgdSynonyms = new HashSet<>();
        for( TermSynonym termSynonym: synonyms1 ) {

            // create a copy of the synonym
            TermSynonym ts = new TermSynonym();
            ts.setKey(termSynonym.getKey());
            ts.setTermAcc(termSynonym.getTermAcc());
            ts.setName(termSynonym.getName());
            ts.setSource(termSynonym.getSource());
            ts.setType("xref");

            inRgdSynonyms.add(ts);
        }

        Set<TermSynonym> incomingSynonyms = new HashSet<>();
        for( TermSynonym termSynonym: synonyms2 ) {

            // xref name cannot be like 'EFO:MONDO:0000111' --- convert it to 'MONDO:0000111'
            String acc = termSynonym.getTermAcc();
            int posColonFirst = acc.indexOf(':');
            int posColonLast = acc.lastIndexOf(':');
            if( posColonFirst <= 0 ) {
                continue;
            }
            if( posColonLast > posColonFirst ) {
                acc = acc.substring(posColonFirst+1);
            }

            String name = termSynonym.getName();
            posColonFirst = name.indexOf(':');
            posColonLast = name.lastIndexOf(':');
            if( posColonFirst <= 0 ) {
                continue;
            }
            if( posColonLast > posColonFirst ) {
                name = name.substring(posColonFirst+1);
            }
            if( name.equals(acc) ) {
                continue;
            }

            TermSynonym tsyn = new TermSynonym();
            tsyn.setTermAcc(name);
            tsyn.setType("xref");
            tsyn.setName(acc);
            incomingSynonyms.add(tsyn);
        }


        List<TermSynonym> xrefsToBeInserted = null;
        List<TermSynonym> xrefsMatching = null;
        List<TermSynonym> xrefsToBeDeleted = null;

        // determine to-be-inserted ids
        xrefsToBeInserted = new ArrayList<>(CollectionUtils.subtract(incomingSynonyms, inRgdSynonyms));

        // determine matching ids
        xrefsMatching = new ArrayList<>(CollectionUtils.intersection(incomingSynonyms, inRgdSynonyms));

        // determine to-be-deleted ids
        xrefsToBeDeleted = new ArrayList<>(CollectionUtils.subtract(inRgdSynonyms, incomingSynonyms));


        // to-be-inserted qc: remove synonyms for obsolete terms
        List<TermSynonym> xrefsToBeInsertedFinal = null;
        if( !xrefsToBeInserted.isEmpty() ) {

            xrefsToBeInsertedFinal = new ArrayList<>();
            int obsoleteTerms = 0;

            for( TermSynonym termSynonym: xrefsToBeInserted ) {

                // do not insert to obsolete terms
                Term t = dao.getTerm(termSynonym.getTermAcc());
                if( t==null || t.isObsolete() ) {
                    obsoleteTerms++;
                    continue;
                }
                xrefsToBeInsertedFinal.add(termSynonym);
            }
            if( obsoleteTerms>0 ) {
                System.out.println("to-be-inserted xrefs: skipped "+obsoleteTerms+" term synonyms because their terms are obsolete" );
            }
        }

        List<TermSynonym> xrefsToBeDeletedFinal = null;
        if( !xrefsToBeDeleted.isEmpty() ) {

            // do not delete term synonyms that have source different from 'SOURCE'
            xrefsToBeDeletedFinal = new ArrayList<>();

            for( TermSynonym xref: xrefsToBeDeleted ) {
                if( xref.getSource().equals(SOURCE) ) {
                    xrefsToBeDeletedFinal.add(xref);
                }
            }
            int toDeleteSynonymsSuppressed = xrefsToBeDeleted.size() - xrefsToBeDeletedFinal.size();
            if( toDeleteSynonymsSuppressed != 0 ) {
                System.out.println("to delete xrefs suppressed from deletion (source other than " + SOURCE + "): " + toDeleteSynonymsSuppressed);
            }
        }

        /////////////////

        // loading
        if( xrefsToBeInsertedFinal != null ) {

            for( TermSynonym termSynonym: xrefsToBeInsertedFinal ) {

                try {
                    dao.insertTermSynonym(termSynonym, SOURCE);
                } catch( Exception e) {
                    System.out.println("error");
                }
            }
            if( xrefsToBeInsertedFinal.size() > 0 ) {
                System.out.println("inserted xrefs: " + Utils.formatThousands(xrefsToBeInsertedFinal.size()));
                totalXrefsInserted += xrefsToBeInsertedFinal.size();
            }
        }

        if( xrefsToBeDeletedFinal != null ) {


            BufferedWriter out = Utils.openWriter("/tmp/to_be_deleted.txt");
            Collections.sort(xrefsToBeDeletedFinal, new Comparator<TermSynonym>() {
                @Override
                public int compare(TermSynonym o1, TermSynonym o2) {
                    return o1.getTermAcc().compareToIgnoreCase(o2.getTermAcc());
                }
            });
            for( TermSynonym t: xrefsToBeDeletedFinal ) {
                out.write( t.dump("|")+"\n");
            }
            out.close();

            dao.deleteTermSynonyms(xrefsToBeDeletedFinal);
            if( xrefsToBeDeletedFinal.size()>0 ) {
                System.out.println("deleted xrefs:  " + xrefsToBeDeletedFinal.size());
                totalXrefsDeleted += xrefsToBeDeletedFinal.size();
            }
        }


        if( !xrefsMatching.isEmpty() ) {

            // do not update last modified date for xrefs with source other than 'RGDSYNC'
            int toUpdateSynonymsSuppressed = 0;
            Iterator<TermSynonym> it = xrefsMatching.iterator();
            while( it.hasNext() ) {
                TermSynonym tsyn = it.next();
                if( !Utils.stringsAreEqual(tsyn.getSource(), SOURCE) ) {
                    toUpdateSynonymsSuppressed++;
                    it.remove();
                }
            }
            if( toUpdateSynonymsSuppressed >0 ) {
                System.out.println("matching xrefs suppressed from update: " + toUpdateSynonymsSuppressed);
            }

            dao.updateTermSynonymLastModifiedDate(xrefsMatching);
            if( xrefsMatching.size() > 0 ) {
                System.out.println("last-modified-date updated for xrefs: " + Utils.formatThousands(xrefsMatching.size()));
                totalXrefsUpToDate += xrefsMatching.size();
            }
        }

    }
}
