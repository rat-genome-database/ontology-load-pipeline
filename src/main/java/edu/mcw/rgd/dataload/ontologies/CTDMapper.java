package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.datamodel.ontologyx.Relation;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.pipelines.PipelineRecord;
import edu.mcw.rgd.process.Utils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;

import java.util.*;

/**
 * @author mtutaj
 * @since 6/10/11
 * <p>
 * CTD ontology contains entries with ids like 'MESH:D00001234' or 'OMIM:001234'.
 * Instead we want to automatically assign ids like 'RDO:0000001' etc
 * and the original ids will become synonyms of type 'primary_id'
 */
public class CTDMapper {

    /** OBSOLETED AS of JAN 1, 2018
    public final String ONT_ID = "RDO";
    protected final Logger logger = Logger.getLogger("ctd_mapper");

    private OntologyDAO dao = new OntologyDAO();
    private Map<String,String> excludedSynonyms = null;

    // map of existing MESH:xxx or OMIM:xxx ids to RDO:xxx ids
    private Map<String, String> ctdmap = new HashMap<String, String>(10003);

    private String version;

    private static CTDMapper _instance = null;

    static public CTDMapper getInstance(Map<String,String> excludedSynonyms) throws Exception {
        if( _instance==null ) {
            DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
            new XmlBeanDefinitionReader(bf).loadBeanDefinitions(new FileSystemResource("properties/AppConfigure.xml"));
            _instance = (CTDMapper) (bf.getBean("ctdMapper"));
            _instance.init(excludedSynonyms);
        }
        return _instance;
    }

    void init(Map<String,String> excludedSynonyms) throws Exception {

        this.excludedSynonyms = excludedSynonyms;

        // load existing synonyms
        for( TermSynonym syn: dao.getActiveSynonymsByType(ONT_ID, "primary_id") ) {
            ctdmap.put(syn.getName(), syn.getTermAcc());
        }

        System.out.println(getVersion());
    }

    public void process(PipelineRecord r) throws Exception {
        Record rec = (Record) r;

        // this filter does acc_id remapping for CDT ontology only
        if( !rec.getTerm().getOntologyId().equals(ONT_ID) )
            return;

        // remap the term acc id from MESH:/OMIM: into RDO:
        String oldAccId = rec.getTerm().getAccId();

        String rdoAccId = getRdoAccId(oldAccId);
        if( rdoAccId==null ) {
            rdoAccId = getRdoAccIdByAltIdSynonyms(rec);
        }

        if( rdoAccId==null ) {
            logger.warn("WARN: term ["+rec.getTerm().getTerm()+"] with id:"+oldAccId+" skipped");
            rec.getTerm().setAccId( rdoAccId );
            return;
        }

        if( !oldAccId.equals(rdoAccId) ) {
            rec.getTerm().setAccId( rdoAccId );

            // remap accession ids for all synonyms
            remapSynonyms(rec.getSynonyms(), rdoAccId);
            // add a new artificial synonym
            rec.addSynonym(oldAccId, "primary_id", excludedSynonyms);

            // remap edges
            remapEdges(rec.getEdges());
        }

        logger.debug(oldAccId+" --> "+rdoAccId);
    }

    public String getRdoAccId(String accId) {
        // handle custom RDO terms
        if( accId.startsWith("DOID:9") ) {
            return accId;
        }

        // get RDO term based on MESH or OMIM id ('primary_id' synonym)
        String rdoAccId = ctdmap.get(accId);
        if( rdoAccId==null ) {
            logger.error("ERROR! Cannot map "+accId+" to RDO term!");
        }
        return rdoAccId;
    }

    private void remapSynonyms(List<TermSynonym> synonyms, String accId) {

        for( TermSynonym syn: synonyms ) {
            syn.setTermAcc(accId);
        }
    }

    private void remapEdges(Map<String,Relation> edges) throws Exception {

        // build alternative edge map
        Map<String,Relation> edges2 = new HashMap<String, Relation>();

        // add CDT acc ids for all non-CDT edges
        for( Map.Entry<String,Relation> entry: edges.entrySet() ) {
            if( entry.getKey().startsWith(ONT_ID)) {
                edges2.put(entry.getKey(), entry.getValue());
            } else {
                edges2.put(getRdoAccId(entry.getKey()), entry.getValue());
            }
        }

        // remove old edges and replace them with new edges
        edges.clear();
        edges.putAll(edges2);
    }

    String getRdoAccIdByAltIdSynonyms(Record rec) {

        // look for 'alt_id' synonyms
        for (TermSynonym tsyn : rec.getSynonyms()) {
            if( Utils.stringsAreEqual(tsyn.getType(), "alt_id") ) {
                String rdoAccId = getRdoAccId(tsyn.getName());
                if( rdoAccId!=null ) {
                    logger.info("secondary mapping of "+tsyn.getName()+" to "+rdoAccId);
                    return rdoAccId;
                }
            }
        }
        return null;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getVersion() {
        return version;
    }
     */
}
