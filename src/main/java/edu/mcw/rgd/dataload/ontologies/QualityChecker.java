package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.datamodel.ontologyx.Relation;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.CounterPool;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * @author mtutaj
 * @since 12/29/10
 * Performs QC for given term
 */
public class QualityChecker {

    private OntologyDAO dao;

    protected final Logger logger = LogManager.getLogger("qc");
    protected final Logger logTermNameChanged = LogManager.getLogger("termNameChanged");

    /**
     * process one unique ontology term; first ensures that given term and all its parent terms
     * exist in database; then checks whether any of term properties (term name, synonyms, etc) have to be updated
     * @param rec Record object
     * @throws Exception
     */
    public void process(Record rec, CounterPool counters) throws Exception {

        // look for term with given term-acc in database
        String termAcc = rec.getTerm().getAccId();
        logger.debug("processing recno="+rec.getRecNo()+" "+termAcc);

        // report terms with missing accession ids (possible for new unmapped RDO terms)
        if( termAcc==null ) {
            rec.setFlag("NO_ACC_ID");
            return;
        }

        if( termAcc.equals("CL:0000584")) {
            // as of Jan 05, 2025: drop cyclic relationship 'develops-from CL:4047019'
            if( rec.getEdges().remove("CL:4047019", Relation.DEVELOPS_FROM) ) {
                LogManager.getLogger("status").warn("  WARNING! removed relationship 'develops-from CL:4047019' from CL:0000584 to avoid cycles!");
            }
        }
        // ensure that both the term being processed
        // and all terms being parent terms of the processed term
        // are in the database
        ensureTermsAreInDatabase(rec, counters);
        // retrieve term from database
        Term term = dao.getTerm(termAcc);

        rec.setFlag("MATCH");
        counters.increment("TERMS_MATCHED");

        qcRelations(rec, counters);

        // for RDO ontology, do term name fixup
        List<TermSynonym> synonymsInRgd = dao.getTermSynonyms(termAcc);
        termNameFixup(term, synonymsInRgd, rec);

        // if incoming data has no definition, but there is a term definition in database
        // then use the definition that is in the database!
        termDefFixup(rec.getTerm(), term);

        // update the term properties if the term found in database differs from incoming term
        if( !rec.getTerm().equals(term) ) {

            // see if term name has been changed
            if( !Utils.stringsAreEqual(term.getTerm(), rec.getTerm().getTerm()) ) {
                logTermNameChanged.info("TERM NAME CHANGED for "+rec.getTerm().getAccId()+" old=["+term.getTerm()+"] new=["+rec.getTerm().getTerm()+"]");
            }

            logger.debug("QC UPDATE term "+rec.getTerm().getAccId()+" ["+rec.getTerm().getTerm()+"]");
            rec.setFlag("UPDATE");
        }

        handleSynonyms(synonymsInRgd, rec, counters);

        rec.qcXRefs(dao);

        checkForCycles(rec, counters);
    }

    void checkForCycles(Record rec, CounterPool counters) {
        for( Map.Entry<String, Relation> entry: rec.getEdges().entrySet() ) {
            String parentTermAcc = entry.getKey();
            String childTermAcc = rec.getTerm().getAccId();
            if( parentTermAcc==null || childTermAcc==null ) {
                continue;
            }

            if( !parentTermAcc.equals(childTermAcc) ) {

                // test if the newly inserted DAG does not form loops
                try {
                    dao.getAncestorCount(parentTermAcc);
                } catch(Exception e) {
                        // connect by loop detected: report it
                    String relId = Relation.getRelIdFromRel(entry.getValue());
                    System.out.println("WARNING: CYCLE found for "+parentTermAcc+" "+Relation.getRelFromRelId(relId)+" "+childTermAcc);
                    counters.increment("TERMS_WITH_CYCLES");
                }
            }
        }
    }

    private void termNameFixup(Term termInDb, List<TermSynonym> synonymsInRgd, Record rec) {

        // fixup for HP root term name:
        //   it is 'All'
        //   we want to change it to 'Human phenotype'
        if( rec.getTerm().getAccId().equals("HP:0000001") ) {
            String oldRootTermName = rec.getTerm().getTerm();
            String newRootTermName = "Human phenotype";

            rec.addSynonym(oldRootTermName, "exact_synonym");
            rec.getTerm().setTerm(newRootTermName);
            logTermNameChanged.info("TERM NAME CHANGED for "+rec.getTerm().getAccId()+" old=["+oldRootTermName+"] new=["+newRootTermName+"]");
            return;
        }

        // common case for RDO ontology:
        // 1. CTD file has term name [MUCKLE-WELLS SYNDROME]
        // 2. RGD database has term name [Muckle-Wells, Syndrome]
        // 3. we want to keep the term name that is in the database !
        // 4.   we call names 1. and 2. as equivalent
        if( !termInDb.getOntologyId().equals("RDO") ) {
            return;
        }

        // if term names differ by case, honor the term name in RGD
        String termNameInDb = termInDb.getTerm();
        String termNameIncoming = rec.getTerm().getTerm();
        if( termNameIncoming==null )
            return;

        // incoming term name same as in RGD db
        if( Utils.stringsAreEqual(termNameInDb, termNameIncoming) )
            return;

        // incoming term name is different than in RGD
        //
        // if incoming term name is equivalent to database term name, keep the db term name
        String termNameInDbEquivalent = rec.synonymManager.process(termNameInDb, "");
        String termNameIncomingEquivalent = rec.synonymManager.process(termNameIncoming, "");

        if( termNameInDbEquivalent.equals(termNameIncomingEquivalent) ) {
            // set the incoming term name to the term name in database
            rec.getTerm().setTerm(termNameInDb);
        } else {
            // incoming term name equivalent is different then the term name equivalent in a RGD
            //   and if incoming term name equivalent is same as existing synonym name equivalent,
            // then honor the term name in RGD
            for( TermSynonym syn: synonymsInRgd ) {
                String synNameEquivalent = rec.synonymManager.process(syn.getName(), "");
                if( synNameEquivalent.equals(termNameIncomingEquivalent) ) {
                    // set the incoming term name to the term name in database
                    rec.getTerm().setTerm(termNameInDb);
                    return;
                }
            }
        }
    }

    // if incoming term has no definition, but there is a term definition in database
    // then use the definition that is in the database!
    private void termDefFixup(Term termIncoming, Term termInRgd) {

        if( Utils.isStringEmpty(termIncoming.getDefinition()) &&
                !Utils.isStringEmpty(termInRgd.getDefinition()) ) {
            termIncoming.setDefinition(termInRgd.getDefinition());
        }
    }

    void qcRelations(Record rec, CounterPool counters) throws Exception {

        String term1OntId = rec.getTerm().getOntologyId();

        Iterator<String> it = rec.getEdges().keySet().iterator();
        while( it.hasNext() ) {
            String term2Acc = it.next();
            if( term2Acc==null ) {
                continue;
            }
            String term2OntId = dao.getTerm(term2Acc).getOntologyId();
            if( !term1OntId.equals(term2OntId) ) {
                counters.increment("DAG_EDGES_CROSS_ONTOLOGY");
                it.remove();
            }
        }
    }

    private void handleSynonyms( List<TermSynonym> synonymsInRgd, Record rec, CounterPool counters) throws Exception {

        rec.qcSynonyms(synonymsInRgd, counters);
    }

    private void ensureTermsAreInDatabase(Record rec, CounterPool counters) throws Exception {

        List<Term> terms = new LinkedList<>();

        // look for term with given term-acc in database
        terms.add(rec.getTerm());

        // check if terms for parent edges do exist in db; if not add them :-)
        for( String parentTermAcc: rec.getEdges().keySet()) {

            Term parentTerm = new Term();
            parentTerm.setOntologyId(rec.getTerm().getOntologyId());
            parentTerm.setAccId(parentTermAcc);
            terms.add(parentTerm);
        }

        dao.ensureTermsAreInDatabase(terms, counters);
    }

    public OntologyDAO getDao() {
        return dao;
    }

    public void setDao(OntologyDAO dao) {
        this.dao = dao;
    }
}
