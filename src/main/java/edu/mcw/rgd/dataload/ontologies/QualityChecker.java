package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.datamodel.ontologyx.Relation;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.pipelines.PipelineRecord;
import edu.mcw.rgd.pipelines.RecordProcessor;
import edu.mcw.rgd.process.Utils;
import org.apache.log4j.Logger;

import java.sql.SQLException;
import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * User: mtutaj
 * Date: 12/29/10
 * Time: 9:15 AM
 * Performs QC for given term
 */
public class QualityChecker extends RecordProcessor {

    private OntologyDAO dao;

    protected final Logger logger = Logger.getLogger("qc");
    private String version;
    protected final Logger logTermNameChanged = Logger.getLogger("termNameChanged");

    public QualityChecker() {
        logger.info(getVersion());
    }

    /**
     * process one unique ontology term; first ensures that given term and all its parent terms
     * exist in database; then checks whether any of term properties (term name, synonyms, etc) have to be updated
     * @param r PipelineRecord object
     * @throws Exception
     */
    public void process(PipelineRecord r) throws Exception {
        Record rec = (Record) r;

        // look for term with given term-acc in database
        String termAcc = rec.getTerm().getAccId();
        logger.debug("processing recno="+rec.getRecNo()+" "+termAcc);

        // report terms with missing accession ids (possible for new unmapped RDO terms)
        if( termAcc==null ) {
            rec.setFlag("NO_ACC_ID");
            return;
        }

        // ensure that both the term being processed
        // and all terms being parent terms of the processed term
        // are in the database
        ensureTermsAreInDatabase(rec);
        // retrieve term from database
        Term term = dao.getTerm(termAcc);

        rec.setFlag("MATCH");
        getSession().incrementCounter("TERMS_MATCHED", 1);

        qcRelations(rec);

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

        handleSynonyms(synonymsInRgd, rec);

        rec.qcXRefs(dao);

        checkForCycles(rec);
    }

    void checkForCycles(Record rec) throws Exception {
        for( Map.Entry<String, Relation> entry: rec.getEdges().entrySet() ) {
            String parentTermAcc = entry.getKey();
            String childTermAcc = rec.getTerm().getAccId();
            if( parentTermAcc==null || childTermAcc==null ) {
                continue;
            }

            if( !parentTermAcc.equals(childTermAcc) ) {

                // test if the newly inserted DAG does not form loops
                try {
                    dao.getDescendantCount(parentTermAcc);
                } catch(Exception e) {
                        // connect by loop detected: report it
                    String relId = Relation.getRelIdFromRel(entry.getValue());
                    System.out.println("WARNING: CYCLE found for "+parentTermAcc+" "+Relation.getRelFromRelId(relId)+" "+childTermAcc);
                    getSession().incrementCounter("TERMS_WITH_CYCLES", 1);
                }
            }
        }
    }

    void checkForCyclesOld(Record rec) throws Exception {
        for( Map.Entry<String, Relation> entry: rec.getEdges().entrySet() ) {
            String parentTermAcc = entry.getKey();
            String childTermAcc = rec.getTerm().getAccId();
            if( parentTermAcc==null || childTermAcc==null ) {
                continue;
            }

            if( !parentTermAcc.equals(childTermAcc) ) {

                // test if the newly inserted DAG does not form loops
                try {
                    dao.getDescendantCount(parentTermAcc);
                } catch(SQLException e) {
                    // we intercept only these exceptions: 'ORA-01436: CONNECT BY loop in user data'
                    if( e.getErrorCode()==1436 ) {
                        // connect by loop detected: report it
                        String relId = Relation.getRelIdFromRel(entry.getValue());
                        System.out.println("WARNING: CYCLE found for "+parentTermAcc+" "+Relation.getRelFromRelId(relId)+" "+childTermAcc);
                        getSession().incrementCounter("TERMS_WITH_CYCLES", 1);
                    }
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

    void qcRelations(Record rec) throws Exception {

        String term1OntId = rec.getTerm().getOntologyId();

        Iterator<String> it = rec.getEdges().keySet().iterator();
        while( it.hasNext() ) {
            String term2Acc = it.next();
            if( term2Acc==null ) {
                continue;
            }
            String term2OntId = dao.getTerm(term2Acc).getOntologyId();
            if( !term1OntId.equals(term2OntId) ) {
                getSession().incrementCounter("DAG_EDGES_CROSS_ONTOLOGY", 1);
                it.remove();
            }
        }
    }

    private void handleSynonyms( List<TermSynonym> synonymsInRgd, Record rec) throws Exception {

        rec.qcSynonyms(synonymsInRgd, getSession());
    }

    private void ensureTermsAreInDatabase(Record rec) throws Exception {

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

        dao.ensureTermsAreInDatabase(terms, getSession());
    }

    public OntologyDAO getDao() {
        return dao;
    }

    public void setDao(OntologyDAO dao) {
        this.dao = dao;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getVersion() {
        return version;
    }
}
