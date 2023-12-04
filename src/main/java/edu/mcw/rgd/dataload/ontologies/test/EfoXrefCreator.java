package edu.mcw.rgd.dataload.ontologies.test;

import edu.mcw.rgd.dataload.ontologies.OntologyDAO;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.Utils;
import org.apache.commons.collections4.CollectionUtils;

import java.util.*;

public class EfoXrefCreator {

    private static final String SOURCE = "RGDLOAD";
    private OntologyDAO dao = new OntologyDAO();

    public static void main(String[] args) throws Exception {

        try {
            new EfoXrefCreator().run();
        } catch( Exception e ) {
            e.printStackTrace();
        }

    }

    void run() throws Exception {

        Set<TermSynonym> inRgdSynonyms = new HashSet<>();
        String[] prefixes = {"CMO:%", "MP:%", "HP:%", "DOID:%"};
        for( String prefix: prefixes ) {
            List<TermSynonym> synonymList = dao.getActiveSynonymsByNamePattern("EFO", prefix);
            for( TermSynonym termSynonym: synonymList ) {
                //if( termSynonym.getSource().equals(SOURCE) ) {
                    inRgdSynonyms.add(termSynonym);
                //}
            }
        }
        System.out.println(" loaded EFO xrefs for EFO ontology: "+inRgdSynonyms.size());


        String[] ontologies = {"CMO", "MP", "HP", "RDO"};
        List<TermSynonym> nonEfoSynonyms = new ArrayList<>();
        for( String ontId: ontologies ) {
            nonEfoSynonyms.addAll(dao.getActiveSynonymsByNamePattern(ontId, "EFO:%"));
        }
        System.out.println(" loaded EFO xrefs for CMO, MO, HP and RDO ontologies: "+nonEfoSynonyms.size());


        // convert into prospective EFO synonyms
        Set<TermSynonym> incomingSynonyms = new HashSet<>();
        for( TermSynonym termSynonym: nonEfoSynonyms ) {
            TermSynonym tsyn = new TermSynonym();
            tsyn.setTermAcc(termSynonym.getName());
            tsyn.setType(termSynonym.getType());
            tsyn.setName(termSynonym.getTermAcc());
            incomingSynonyms.add(tsyn);
        }
        System.out.println(" incoming EFO xrefs: "+incomingSynonyms.size());



        List<TermSynonym> xrefsToBeInserted = null;
        List<TermSynonym> xrefsMatching = null;
        List<TermSynonym> xrefsToBeDeleted = null;

        // determine to-be-inserted ids
        System.out.println("QC: determine to-be-inserted xrefs");
        xrefsToBeInserted = new ArrayList<>(CollectionUtils.subtract(incomingSynonyms, inRgdSynonyms));

        // determine matching ids
        System.out.println("QC: determine matching xrefs");
        xrefsMatching = new ArrayList<>(CollectionUtils.intersection(incomingSynonyms, inRgdSynonyms));

        // determine to-be-deleted ids
        System.out.println("QC: determine to-be-deleted xrefs");
        xrefsToBeDeleted = new ArrayList<>(CollectionUtils.subtract(inRgdSynonyms, incomingSynonyms));


        // loading
        if( !xrefsToBeInserted.isEmpty() ) {
            for( TermSynonym termSynonym: xrefsToBeInserted ) {

                // do not insert to obsolete terms
                Term t = dao.getTerm(termSynonym.getTermAcc());
                if( t.isObsolete() ) {
                    continue;
                }

                try {
                    dao.insertTermSynonym(termSynonym, SOURCE);
                } catch( Exception e) {
                    System.out.println("error");
                }
            }
            System.out.println("inserted xdb ids for GTEx: "+ Utils.formatThousands(xrefsToBeInserted.size()));
        }

        if( !xrefsToBeDeleted.isEmpty() ) {
            // do not delete term synonyms that have source different from 'SOURCE'
            int toDeleteSynonymsSuppressed = 0;
            Iterator<TermSynonym> it = xrefsToBeDeleted.iterator();
            while( it.hasNext() ) {
                TermSynonym tsyn = it.next();
                if( !tsyn.getSource().equals(SOURCE) ) {
                    toDeleteSynonymsSuppressed++;
                    it.remove();
                }
            }
            System.out.println("to delete xrefs suppressed from deletion: "+toDeleteSynonymsSuppressed);

            dao.deleteTermSynonyms(xrefsToBeDeleted);
            System.out.println("deleted EFO xrefs:  "+xrefsToBeDeleted.size());
        }

        if( !xrefsMatching.isEmpty() ) {

            // do not update last modified date for xrefs with source other than 'RGDLOAD'
            int toUpdateSynonymsSuppressed = 0;
            Iterator<TermSynonym> it = xrefsMatching.iterator();
            while( it.hasNext() ) {
                TermSynonym tsyn = it.next();
                if( !Utils.stringsAreEqual(tsyn.getSource(), SOURCE) ) {
                    toUpdateSynonymsSuppressed++;
                    it.remove();
                }
            }
            System.out.println("matching xrefs suppressed from update: "+toUpdateSynonymsSuppressed);

            dao.updateTermSynonymLastModifiedDate(xrefsMatching);
            System.out.println("last-modified-date updated for EFO xrefs: "+Utils.formatThousands(xrefsMatching.size()));
        }
    }
}
