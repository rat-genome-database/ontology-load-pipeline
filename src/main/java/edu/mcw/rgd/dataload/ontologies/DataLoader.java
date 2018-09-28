package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.datamodel.ontologyx.Relation;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.pipelines.PipelineRecord;
import edu.mcw.rgd.pipelines.RecordProcessor;
import org.apache.log4j.Logger;

import java.util.Map;

/**
 * @author mtutaj
 * @since 12/29/10
 */
public class DataLoader extends RecordProcessor {

    private OntologyDAO dao;
    protected final Logger logger = Logger.getLogger("data_loader");
    private String version;

    public DataLoader() {
        logger.info(getVersion());
    }

    public void process(PipelineRecord r) throws Exception {
        Record rec = (Record) r;
        Term term = rec.getTerm();

        logger.debug("processing ["+rec.getRecNo()+".] "+term.getAccId());

        if( rec.isFlagSet("NO_ACC_ID") ) {
            getSession().incrementCounter("TERMS_MISSING_ACC_ID", 1);
            return;
        }

        if( rec.isFlagSet("UPDATE") ) {
            logger.debug("UPDATE ACC_ID:"+term.getAccId()+", TERM:"+term.getTerm()+", ISOBSOLETE:"+term.getObsolete()+", DEFINITION:"+term.getDefinition()+", COMMENT:"+term.getComment());
            dao.updateTerm(term);
            getSession().incrementCounter("TERMS_UPDATED", 1);
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
                    getSession().incrementCounter("DAG_EDGES_INCOMING", 1);
                }
            }
        }

        // handle synonyms
        loadSynonyms(rec);

        // handle dbxrefs
        rec.loadXRefs(dao, getSession());
    }

    void loadSynonyms(Record rec) throws Exception {

        // insert new synonyms
        if( !rec.getSynonymsForInsert().isEmpty() ) {
            for( TermSynonym synonym: rec.getSynonymsForInsert() ) {

                if( dao.insertTermSynonym(synonym, null) ) {
                    getSession().incrementCounter("SYNONYMS_INSERTED", 1);
                } else {
                    getSession().incrementCounter("SYNONYMS_FOR_INSERT_SKIPPED", 1);
                }
            }
        }

        // update synonyms
        if( !rec.getSynonymsForUpdate().isEmpty() ) {
            dao.updateTermSynonymLastModifiedDate(rec.getSynonymsForUpdate());
            //getSession().incrementCounter("SYNONYMS_UPDATE_LAST_MOD_DATE", rec.getSynonymsForUpdate().size());
        }

        // delete synonyms
        if( !rec.getSynonymsForDelete().isEmpty() ) {
            int deleted = dao.deleteTermSynonyms(rec.getSynonymsForDelete());
            int skipped = rec.getSynonymsForDelete().size() - deleted;
            getSession().incrementCounter("SYNONYMS_DELETED", deleted);
            getSession().incrementCounter("SYNONYMS_FOR_DELETE_SKIPPED", skipped);
        }
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
