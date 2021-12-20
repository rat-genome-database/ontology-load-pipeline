package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.datamodel.ontologyx.Relation;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.CounterPool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/**
 * @author mtutaj
 * @since 12/29/10
 */
public class DataLoader {

    private OntologyDAO dao;
    protected final Logger logger = LogManager.getLogger("data_loader");

    public void process(Record rec, CounterPool counters) throws Exception {
        Term term = rec.getTerm();

        logger.debug("processing ["+rec.getRecNo()+".] "+term.getAccId());

        if( rec.isFlagSet("NO_ACC_ID") ) {
            counters.increment("TERMS_MISSING_ACC_ID");
            return;
        }

        if( rec.isFlagSet("UPDATE") ) {
            logger.debug("UPDATE ACC_ID:"+term.getAccId()+", TERM:"+term.getTerm()+", ISOBSOLETE:"+term.getObsolete()+", DEFINITION:"+term.getDefinition()+", COMMENT:"+term.getComment());
            dao.updateTerm(term);
            counters.increment("TERMS_UPDATED");
        }

        if( !rec.getEdges().isEmpty() ) {
            for( Map.Entry<String, Relation> entry: rec.getEdges().entrySet() ) {
                String parentTermAcc = entry.getKey();
                String childTermAcc = term.getAccId();
                if( parentTermAcc==null || childTermAcc==null ) {
                    System.out.println("WARN: NULL in dag: ["+parentTermAcc+"]==>["+childTermAcc+"]");
                    continue;
                }
                if( parentTermAcc.equals(childTermAcc) ) {
                    System.out.println("WARN: parent term acc equals child term acc: "+parentTermAcc);
                }
                else {
                    String relId = Relation.getRelIdFromRel(entry.getValue());
                    logger.debug("UPSERT DAG ("+parentTermAcc+","+childTermAcc+","+entry.getValue()+")");
                    dao.insertDag(parentTermAcc, childTermAcc, relId);
                    counters.increment("DAG_EDGES_INCOMING");
                }
            }
        }

        // handle synonyms
        loadSynonyms(rec, counters);

        // handle dbxrefs
        rec.loadXRefs(dao, counters);
    }

    void loadSynonyms(Record rec, CounterPool counters) throws Exception {

        // insert new synonyms
        if( !rec.getSynonymsForInsert().isEmpty() ) {
            for( TermSynonym synonym: rec.getSynonymsForInsert() ) {

                if( dao.insertTermSynonym(synonym, null) ) {
                    counters.increment("SYNONYMS_INSERTED");
                } else {
                    counters.increment("SYNONYMS_FOR_INSERT_SKIPPED");
                }
            }
        }

        // update synonyms
        if( !rec.getSynonymsForUpdate().isEmpty() ) {
            dao.updateTermSynonymLastModifiedDate(rec.getSynonymsForUpdate());
            //counters.add("SYNONYMS_UPDATE_LAST_MOD_DATE", rec.getSynonymsForUpdate().size());
        }

        // delete synonyms
        if( !rec.getSynonymsForDelete().isEmpty() ) {
            int deleted = dao.deleteTermSynonyms(rec.getSynonymsForDelete());
            int skipped = rec.getSynonymsForDelete().size() - deleted;
            counters.add("SYNONYMS_DELETED", deleted);
            counters.add("SYNONYMS_FOR_DELETE_SKIPPED", skipped);
        }
    }

    public OntologyDAO getDao() {
        return dao;
    }

    public void setDao(OntologyDAO dao) {
        this.dao = dao;
    }
}
