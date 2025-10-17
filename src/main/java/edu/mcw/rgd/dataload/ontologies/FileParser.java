package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.datamodel.ontologyx.*;
import edu.mcw.rgd.process.CounterPool;
import edu.mcw.rgd.process.FileDownloader;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * @author mtutaj
 * Date: 12/29/10
 */
public class FileParser {

    private OntologyDAO dao;

    private Map<String, String> ontPrefixes;
    private Map<String, Relation> relations; // map of relation name as found in obo file to Relation enum
    private Map<String, String> rootTerms;
    private Map<String, String> propertyValueSubstitutions;
    private Map<String, String> relationshipSubstitutions;
    private List<String> ignoredProperties;
    private List<String> propertyToSynonym;

    // to parse creation_date: field in obo files:   '2011-01-04T12:01:33Z'
    static SimpleDateFormat sdtCreationDate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    static SimpleDateFormat sdtCreationDate2 = new SimpleDateFormat("yyyy-MM-dd");

    protected final Logger logger = LogManager.getLogger("file_parser");
    private List<String> ontologiesWithExactMatchSynonyms;
    private Map<String,Date> startTimes = new HashMap<>();
    private Map<String,String> synonymPrefixSubstitutions;
    private String apiKey;
    private CounterPool counters;

    public FileParser() {

        // initialize relations map
        relations = new HashMap<>();
        for( Relation rel: Relation.values() ) {
            // in obo file, all relation names use underscores between words; our toString() uses '-'
            relations.put(rel.toString().replace("-","_"), rel);
        }
        // aliases
        relations.put("RO:0002202", Relation.DEVELOPS_FROM);
    }

    public List<Record> process(String ontId, String path, CounterPool counters) throws Exception {

        this.counters = counters;

        // download the file if its name starts with http or ftp
        logger.info(" ONT_ID="+ontId+" PATH="+path);

        String srcFile = path;
        if( srcFile.contains("##APIKEY##") ) {
            srcFile = srcFile.replace("##APIKEY##", apiKey);
        }

        FileDownloader downloader = new FileDownloader();
        downloader.setExternalFile(srcFile);
        logger.info("input file: "+downloader.getExternalFile());
        downloader.setLocalFile("data/"+ontId+".obo");
        if( path.endsWith(".gz") )
            downloader.setLocalFile(downloader.getLocalFile()+".gz");
        downloader.setPrependDateStamp(true);
        downloader.setUseCompression(true); // use gzip compression when storing the downloaded files

        String localFile = downloader.downloadNew();

        // our file is ready for processing
        Map<String, Record> results = new HashMap<>();
        logger.info("START: "+Thread.currentThread().getName()+" ONT_ID="+ontId+" FILE="+localFile);
        if( processAll(localFile, ontId, ontPrefixes.get(ontId), results) ) {
            logger.info("DONE: "+Thread.currentThread().getName()+" ONT_ID="+ontId+" FILE="+localFile);
        } else {
            logger.warn("MALFORMED OBO FILE: "+Thread.currentThread().getName()+" ONT_ID="+ontId+" FILE="+localFile);

            MalformedOboFiles.getInstance().addOntId(ontId);
        }

        List<Record> records = new ArrayList<>(results.values());
        return records;
    }

    public void postProcess() throws Exception {

        for( String ontId: startTimes.keySet() ) {
            // our file is ready for processing
            logger.info("POSTPROCESS START: "+Thread.currentThread().getName()+" ONT_ID="+ontId);
            postProcess(ontId);
            logger.info("POSTPROCESS DONE: "+Thread.currentThread().getName()+" ONT_ID="+ontId);

            System.out.println("Parsing post processing complete for ONT_ID=" + ontId);
        }
    }

    private BufferedReader openFile(String fileName) throws IOException {

        String encoding = "UTF-8"; // default encoding

        InputStream is;
        if( fileName.endsWith(".gz") ) {
            is = new GZIPInputStream(new FileInputStream(fileName));
        } else {
            is = new FileInputStream(fileName);
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(is, encoding));
        return reader;
    }

    boolean processAll(String fileName, String defaultOntId, String accIdPrefix, Map<String, Record> records) throws Exception {

        Date startTime = Utils.addMinutesToDate(new Date(), -1);
        startTimes.put(defaultOntId, startTime);

        // validate if ontology id is valid
        Ontology ont = dao.getOntology(defaultOntId);
        if( ont==null && !defaultOntId.equals("GO") ) {
            System.out.println("WARNING! INVALID ONTOLOGY ID: "+defaultOntId);
            return true;
        }
        System.out.println("processing ontology "+defaultOntId);

        if( !validateOboFile(fileName) ) {
            return false;
        }

        loadCyclicRelationships(fileName, defaultOntId);

        Map<String, String> rootTermRelations = loadRootTerms(defaultOntId, records);

        records.putAll(process(fileName, defaultOntId, accIdPrefix, rootTermRelations));

        return true;
    }

    void postProcess(String ontId) throws Exception {

        System.out.println("postprocessing ontology "+ontId);

        Date startTime = startTimes.get(ontId);

        // delete all stale dag relations for given ontology
        deleteDagsForOntology(ontId, startTime);

        // dump all inserted dags
        dumpInsertedDagsForOntology(ontId, startTime);
    }

    /**
     * open and read obo file; validate if the obo file is well-formed
     * <p>
     * at a minimum, it must have a header line 'format-version:', and at least 10 lines with fields 'id:' and 'name:'
     * @param fileName name of obo file
     * @return true if obo file is well-formed; false otherwise
     * @throws Exception
     */
    boolean validateOboFile(String fileName) throws Exception {

        BufferedReader reader = openFile(fileName);
        int formatVersionCount = 0;
        int idCount=0;
        int nameCount=0;

        String line;
        while( (line=reader.readLine())!=null ) {
            // no need to read entire file
            if( formatVersionCount>0 && idCount>=10 && nameCount>=10 )
                break;

            if( line.startsWith("format-version:") ) {
                formatVersionCount++;
                continue;
            }
            if( line.startsWith("id:") ) {
                idCount++;
                continue;
            }
            if( line.startsWith("name:") ) {
                nameCount++;
                continue;
            }
        }
        reader.close();
        return formatVersionCount>0 && idCount>=10 && nameCount>=10;
    }

    Map<String, Record> process(String fileName, String defaultOntId, String accIdPrefix,
                 Map<String, String> rootTermRelations) throws Exception {

        Map<String, Record> records = new HashMap<>(4003);

        // read the input stream -- whether zipped or not
        BufferedReader reader = openFile(fileName);

        String line, val;
        Record rec = null;
        while( (line=reader.readLine())!=null ) {

            // detect term boundary
            if( line.startsWith("[") ) {
                // write the current record into queue for further processing
                addRecordToProcessingQueue(rec, records);

                // handle new record
                if( line.contains("[Term]") ) {
                    // create a new empty record
                    rec = new Record();
                    rec.getTerm().setOntologyId(defaultOntId);

                    // setup method of matching synonyms: exact or inexact
                    if( ontologiesWithExactMatchSynonyms.contains(defaultOntId) ) {
                        rec.synonymManager.exactMatchSynonyms = true;
                    }

                    continue;
                }
                else {
                    // other definitions, like [Typedef], are ignored
                    rec = null;
                    continue;
                }
            }

            // skip header lines and empty lines
            if( line.length()==0 )
                continue;
            if( rec==null ) {
                continue;
            }

            // pre-parse line (to do contents rewrite if applicable)
            line = preParseLine(line);

            // parse term fields
            //
            // term_acc
            if( line.startsWith("id:") ) {

                // term accession id
                String accId = line.substring(4).trim();

                // skip terms not matching ontology prefix
                if( !accIdPrefix.equals("*") && !accId.startsWith(accIdPrefix) ) {

                    boolean loadForeignOntologyTerms = accIdPrefix.equals("EFO:");
                    if( loadForeignOntologyTerms ) {
                        // load the original accession as a synonym
                        String accId2;
                        if( accId.startsWith("BAO_") ) { // convert 'EFO:BAO_xxxxxxx' into 'EFO:BAO:xxxxxxx'
                            accId2 = "BAO:"+accId.substring(4);
                        } else {
                            accId2 = accId;
                        }
                        String newAccId = accIdPrefix + accId2;
                        if( newAccId.startsWith("EFO:efo:EFO_") && newAccId.length()==19 ) {
                            newAccId = "EFO:"+newAccId.substring(12);
                        }
                        else if( newAccId.startsWith("efo:EFO_") && newAccId.length()==15 ) {
                            newAccId = "EFO:"+newAccId.substring(8);
                        }
                        addAltIdSynonymIfValid(rec, newAccId, accId);
                        accId = newAccId;

                    } else {
                        // get term prefix
                        int colonPos = accId.indexOf(':');
                        String prefix = colonPos>0 ? accId.substring(0, colonPos) : accId;
                        String counter = "skipped "+prefix+" term(s) for ontology "+defaultOntId;
                        counters.increment(counter);

                        String msg = "term "+accId+" skipped, because it does not match ontology prefix "+defaultOntId;
                        logger.info(msg);
                        rec = null; // to skip this term
                        continue;
                    }
                }

                rec.getTerm().setAccId(accId);

                // generate VT xrefs for OBA:VTxxxxxx terms
                if( accId.startsWith("OBA:VT") && accId.startsWith(accIdPrefix) ) {
                    String xrefLine = "xref: VT:"+accId.substring(6);
                    parseXrefAsSynonym(xrefLine, rec);
                }

                // add relations to artificial root term
                if( rootTermRelations!=null ) {
                    String rootAccId = rootTermRelations.get(rec.getTerm().getAccId());
                    if( rootAccId!=null ) {
                        rec.addEdge(rootAccId, Relation.IS_A);
                    }
                }
            }
            // name
            else if( line.startsWith("name:") ) {
                parseTermName(line.substring(6), rec);
            }
            // def
            else if( (line.startsWith("def:") || line.startsWith("definition:")) && line.length()>5 ) {

                val = line.substring(5);
                // replace [\"] -> [']   to fix problems with quotes double-quotes in the definition text
                val = val.replace("\\\"", "'");
                // replace [\:] -> [:]
                val = val.replace("\\:", ":");

                // extract contents between double quotes
                int firstDoubleQuotesPos = val.indexOf('\"');
                int secondDoubleQuotesPos = val.indexOf('\"', firstDoubleQuotesPos+1);
                if( firstDoubleQuotesPos>=0 && secondDoubleQuotesPos>=0 ) {
                    setDefinition(rec.getTerm(), val.substring(firstDoubleQuotesPos+1, secondDoubleQuotesPos));
                    rec.addIncomingXRefs(parseXrefs(val.substring(secondDoubleQuotesPos + 1)));
                } else {
                    // there are no double quotes in the definition -- use it as it is
                    setDefinition(rec.getTerm(), val.trim());
                    rec.addIncomingXRefs(parseXrefs(val));
                }
            }
            // comment
            else if( parseComment(line, rec) ) {
                // nothing to do here :-)
            }
            // is_obsolete
            else if( line.startsWith("is_obsolete:") ) {
                rec.getTerm().setObsolete(line.substring(13).equals("true")?1:0);
            }
            // is_a
            else if( line.startsWith("is_a:") || line.startsWith("relationship:") ) {
                int spacePos;
                if( line.startsWith("is_a:") ) {
                    spacePos = line.indexOf(' ', 6);
                    if( spacePos > 0 )
                        val = line.substring(6, spacePos);
                    else
                        val = line.substring(6);
                    addRelationship(rec, val, Relation.IS_A, accIdPrefix, "is_a");
                }
                else {
                    parseRelationship(rec, line.substring(13).trim(), accIdPrefix);
                }
            }
            // namespace
            else if( line.startsWith("namespace:") ) {

                // do not override ontology id for EFO ontology! (i.e. skip the line when loading EFO ontology)
                if( !defaultOntId.equals("EFO") ) {

                    // override of namespaces for GO obo file
                    val = line.substring(11);
                    switch (val) {
                        case "biological_process":
                            rec.getTerm().setOntologyId("BP");
                            break;
                        case "molecular_function":
                            rec.getTerm().setOntologyId("MF");
                            break;
                        case "cellular_component":
                            rec.getTerm().setOntologyId("CC");
                            break;
                    }
                }
            }
            // xref
            else if( line.startsWith("xref:") ) {
                parseXrefAsSynonym(line.substring(6), rec);
            }
            // synonyms
            else if( parseSynonym(line, rec) ) {
                // nothing to do here :-)
            }
            // created_by
            else if( line.startsWith("created_by:") ) {
                // per OBO spec, there could be only one 'created_by' field; merge incoming data into one field
                String createdBy = line.substring(12);
                if( Utils.isStringEmpty(rec.getTerm().getCreatedBy()) ) {
                    rec.getTerm().setCreatedBy(createdBy);
                } else if( !rec.getTerm().getCreatedBy().contains(createdBy) ) {
                    rec.getTerm().setCreatedBy( rec.getTerm().getCreatedBy()+", "+createdBy );
                }
            }
            // creation_date
            else if( line.startsWith("creation_date:") || line.startsWith("date:")) {
                int datePos = line.indexOf(": ") + 2;
                String dt = line.substring(datePos);
                if( dt.startsWith("\"") ) {
                    dt = dt.substring(1).trim();
                }
                if( dt.endsWith("\"") ) {
                    dt = dt.substring(0, dt.length()-1).trim();
                }
                synchronized(this) {
                    // creation_date: field in obo files:   '2011-01-04T12:01:33Z' or '2011-01-04'
                    Date creationDate;
                    try {
                        creationDate = sdtCreationDate.parse(dt);
                    } catch( ParseException e ) {
                        creationDate = sdtCreationDate2.parse(dt);
                    }
                    rec.getTerm().setCreationDate(creationDate);
                }
            }
            // subset:
            else if( line.startsWith("subset:") ) {
                parseSubset(line.substring(7), rec);
            }

            // IGNORED ATTRIBUTES
            else if( !parseIgnored(line, rec) ) {
                System.out.println("*IGNORED FIELD*: "+line);
            }
        }

        // write the current record into queue for further processing
        addRecordToProcessingQueue(rec, records);

        reader.close();

        return records;
    }

    void parseSubset(String value, Record rec) {

        // parse subsets for CVCL ontology
        if( rec.getTerm().getAccId().startsWith("CVCL") ) {
            rec.addSynonym(value.trim(), "subset");
            return;
        }

        // special cases of ignored field 'subset:'
        if( value.contains("gocheck_do_not_annotate") || value.contains("gocheck_do_not_manually_annotate") ) {
            rec.addSynonym("Not4Curation", "synonym");
            counters.increment("SYNONYMS_GO_NOT4CURATION");
        }
    }

    // cleanup of 'xref:' lines in EFO ontology
    void parseXrefAsSynonym(String line, Record rec) {

        // remove comments from xrefs, f.e.
        //   xref: HP:0006779 {source="MONDO:otherHierarchy", source="ontobio"}
        // rewrite as
        //   xref: HP:0006779
        String xref = line.trim();
        if( xref.endsWith("}") ) {
            int pos = xref.lastIndexOf('{');
            if( pos>0 ) {
                xref = xref.substring(0, pos).trim();
            }
        }

        // MESH normalization: in RGD, we use 'MESH:xxx' convention; HP uses 'MSH:xxxxx'
        if( xref.startsWith("MSH:") ) {
            xref = "MESH:"+xref.substring(4); // turn 'MSH:xxx' into 'MESH:xxx'
        }

        // EFO normalization:
        // 1. 'Orphanet:xxx' --> 'ORDO:xxx'
        else if( xref.startsWith("Orphanet:") ) {
            xref = "ORDO:"+xref.substring(9);
        }

        // 2. 'MeSH:xxx' --> 'MESH:xxx'
        else if( xref.startsWith("MeSH:") ) {
            xref = "MESH:"+xref.substring(5);
        }

        // 3. 'OMIMPS:xxx' --> 'MIM:PSxxx'
        else if( xref.startsWith("OMIMPS:") ) {
            xref = "MIM:PS"+xref.substring(7);
        }

        // 4. 'NCIt:xxx' --> 'NCI:xxx'
        //    'NCIT:xxx' --> 'NCI:xxx'
        else if( xref.startsWith("NCIT:") || xref.startsWith("NCIt:") ) {
            xref = "NCI:"+xref.substring(5);
        }

        // 5. 'GARD:0000xxx' --> 'GARD:xxx'  strip zeros after colon
        else if( xref.startsWith("GARD:") ) {
            // look for first non-zero character
            int pos;
            for( pos=5; pos<xref.length(); pos++ ) {
                if( xref.charAt(pos) != '0' ) {
                    break;
                }
            }
            xref = "GARD:"+xref.substring(pos);
        }

        // 6. 'OMIM:xxx' --> 'MIM:xxx'
        else if( xref.startsWith("OMIM:") ) {
            xref = xref.substring(1);
        }

        rec.addSynonym(xref, "xref");
    }

    void parseTermName(String line, Record rec) {

        String termNameIncoming = line.trim();

        // in incoming data sometimes we find term names with ';', like the one below:
        //  [Deafness, Autosomal Dominant 22;DFNA22 Deafness, Autosomal Dominant 22, with Hypertrophic Cardiomyopathy,]
        // therefore we use the first part of term name, before ';', as the term name
        // and the rest of the term name as a synonym
        // in our example:
        //  [Deafness, Autosomal Dominant 22] - term name
        //  [DFNA22 Deafness, Autosomal Dominant 22, with Hypertrophic Cardiomyopathy,] -- synonym
        int semicolonPos = termNameIncoming.indexOf(';');
        // note: do not split term name for CVCL ontology or CHEBI ontology!
        if( semicolonPos>=0
                && !(rec.getTerm().getAccId().startsWith("CVCL_") || rec.getTerm().getAccId().startsWith("CHEBI")) ) {

            // ';' must not be surrounded in parentheses, like this:
            // [renal neoplasm with t(6;11)(p21;q12)]
            int openParentheses1 = 0, openParentheses2 = 0;
            int closeParentheses1 = 0, closeParentheses2 = 0;
            for( int i=0; i<termNameIncoming.length(); i++ ) {
                char c = termNameIncoming.charAt(i);
                if( c=='(' ) {
                    if( i<semicolonPos )
                        openParentheses1++;
                    else
                        openParentheses2++;
                }
                else if( c==')' ) {
                    if( i<semicolonPos )
                        closeParentheses1++;
                    else
                        closeParentheses2++;
                }
            }
            if( openParentheses1==closeParentheses1 && openParentheses2==closeParentheses2 ) {

                String[] terms = termNameIncoming.split(";");
                if( terms.length>=2 ) {
                    System.out.println("*** term name split: "+rec.getTerm().getAccId()+" "+termNameIncoming);
                    parseCommentsInTermName(terms[0], rec);

                    for( int i=1; i<terms.length; i++ ) {
                        rec.addSynonym(terms[i], "exact_synonym");
                    }
                    return;
                }
            }
        }

        parseCommentsInTermName(line, rec);
    }

    // currently we strip comments from term name, if any
    void parseCommentsInTermName(String termName, Record rec) {

        // TERM-NAME {comment="HPO:probinson", comment="PMID:24447956", comment="PMID:26929770"}
        termName = termName.trim();
        if( termName.endsWith("}") ) {
            int pos = termName.lastIndexOf('{');
            if( pos>0 ) {
                termName = termName.substring(0, pos).trim();
            }
        }
        rec.getTerm().setTerm(termName);

        // are there any UTF-8 characters
        byte[] b = termName.getBytes();
        if( b.length!=termName.length() ) {
            System.out.println(" utf8 characters in term name: "+termName);
        }
    }

    boolean parseComment(String line, Record rec) {
        if( !line.startsWith("comment:") && !line.startsWith("note") ) {
            return false;
        }

        String commentPrefix = "";
        int pos2 = line.indexOf(": ");
        int pos1 = line.indexOf('_');
        if( pos1>0 && pos1<pos2 )
            commentPrefix = "["+line.substring(pos1+1, pos2)+"-"+line.substring(0, pos1)+"] ";

        // join possibly multiple comments by "; "
        String comment = rec.getTerm().getComment();
        if( comment!=null )
            comment += "; ";
        else
            comment = "";
        comment += commentPrefix + line.substring(pos2+2);
        rec.getTerm().setComment(comment);
        return true;
    }

    boolean parseSynonym(String line, Record rec) {

        for( String property: getPropertyToSynonym() ) {
            if( line.startsWith(property+":") ) {

                // post-processing of synonym name
                String synonymName = line.substring(property.length()+2);

                switch(property) {
                    case "chebi_smiles":
                    case "chebi_formula":
                    case "chebi_inchi":
                    case "chebi_inchikey":
                        return addChebiSynonym(synonymName, property, rec);
                }

                for( Map.Entry<String,String> entry: getSynonymPrefixSubstitutions().entrySet() ) {
                    String synonymNamePrefix = entry.getKey();
                    if( synonymName.startsWith(synonymNamePrefix) ) {
                        // substitute synonym name prefix
                        synonymName = entry.getValue()+synonymName.substring(synonymNamePrefix.length());
                        counters.increment("SYNONYMS_SUBST_NAME_PREFIX_"+synonymNamePrefix);
                    }
                }

                rec.addSynonym(synonymName, property);
                return true;
            }
        }
        return false;
    }

    boolean addChebiSynonym(String synonymName, String property, Record rec) {
        // strip double quotes from synonym name
        if( synonymName.startsWith("\"") && synonymName.endsWith("\"") ) {
            synonymName = synonymName.substring(1, synonymName.length()-1);
        }
        switch(property) {
            case "chebi_smiles":
                synonymName = "SMILES="+synonymName; break;
            case "chebi_formula":
                synonymName = "Formula="+synonymName; break;
            case "chebi_inchikey":
                synonymName = "InChIKey="+synonymName; break;
        }

        TermSynonym syn = new TermSynonym();
        syn.setName(synonymName);
        syn.setType("related_synonym");
        syn.setTermAcc(rec.getTerm().getAccId());
        rec.addSynonym(syn);
        return true;
    }

    boolean parseIgnored(String line, Record rec) {

        for( String ignoredAttr: getIgnoredProperties() ) {
            if( line.startsWith(ignoredAttr) ) {
                counters.increment("ignored field: "+ignoredAttr);
                return true;
            }
        }
        return false;
    }

    void setDefinition(Term term, String text) {

        // merge definitions
        if( term.getDefinition()==null || term.getDefinition().isEmpty() )
            term.setDefinition(text);
        else if( !term.getDefinition().contains(text) ) {
            String newDef = term.getDefinition()+" "+text;
            if( newDef.length()<4000 )
                term.setDefinition(newDef);
        }
    }

    // record to be inserted must have a non-empty accession id;
    void addRecordToProcessingQueue( Record rec, Map<String, Record> records ) throws Exception {

        // write the current record into queue for further processing
        if( rec==null || rec.getTerm()==null )
            return;
        String termAcc = rec.getTerm().getAccId();
        if( termAcc!=null ) {

            Record prevRec = records.put(termAcc, rec);
            if( prevRec!=null ) {
                // merge the both incoming terms
                throw new Exception("duplicate acc id for "+termAcc);
            }
        }
    }

    /**
     * parse lines starting with 'property_value:' attribute;
     * rewrite them into more common attributes like 'created_by', creation_date', 'def', 'namespace', 'synonym'
     * @param line original line contents
     * @return possibly transformed line contents
     */
    String preParseLine(String line) {

        if( line.startsWith("property_value:") ) {

            // real contents
            String contents;
            if( line.endsWith("xsd:string") || line.endsWith("xsd:anyURI") )
                contents = line.substring(15, line.length()-10).trim();
            else
                contents = line.substring(15).trim();

            // process ORCID ids: example
            //   property_value: http://purl.org/dc/terms/contributor https://orcid.org/0000-0003-3691-0324
            // replace with
            //   created_by: ORCID:0000-0003-3691-0324
            if( contents.startsWith(ORCID_PREFIX) ) {
                String newContents = "created_by: ORCID:"+contents.substring(ORCID_PREFIX.length());
                return newContents;
            }
            if( contents.startsWith(ORCID_PREFIX2) ) {
                String newContents = "created_by: ORCID:"+contents.substring(ORCID_PREFIX2.length());
                return newContents;
            }

            for( String property: this.getPropertyValueSubstitutions().keySet() ) {
                if( contents.startsWith(property) ) {
                    String newContents;
                    newContents = this.getPropertyValueSubstitutions().get(property) + ": " + contents.substring(property.length()+1);
                    if( property.equals("synonym") ) {
                        newContents += " RELATED []";
                    }
                    return newContents;
                }
            }
        }
        // no substitutions made -- line returned as it is
        return line;
    }
    static final String ORCID_PREFIX = "http://purl.org/dc/terms/contributor https://orcid.org/";
    static final String ORCID_PREFIX2 = "http://purl.org/dc/terms/contributor http://orcid.org/";

    void parseRelationship(Record rec, String line, String ontId) throws Exception {
        // removed 'OBO_REL:' default namespace id, if present
        if( line.startsWith("OBO_REL:") )
            line = line.substring(8).trim();

        // relation name is a string until next space
        int spacePos = line.indexOf(' ');
        if( spacePos<=0 )
            throw new Exception("Unexpected relationship: "+line);

        // parse relation name
        String relName = convertRelName(line.substring(0, spacePos));
        Relation rel = relations.get(relName);
        if( rel==null ) {
            // MONDO extra processing
            if( ontId.equals("MONDO") && relName.equals("seeAlso") ) {
                rec.addSynonym(line.substring(spacePos).trim(), "seeAlso");
                return;
            }
            if( ontId.equals("MONDO") && relName.equals("excluded_subClassOf") ) {
                // ignore these relationships entirely
                return;
            }
            if( ontId.equals("MONDO") && (relName.equals("disease_has_feature") || relName.equals("disease_shares_features_of")) ) {
                String name = line.substring(spacePos).trim();
                int spacePos2 = name.indexOf(' ');
                if( spacePos2>0 ) {
                    name = name.substring(0, spacePos2);
                }
                rec.addSynonym(name, relName);
                return;
            }
            rel = Relation.NOT_SPECIFIED;
        }

        // term acc id for the relation is between 1st and 2nd space
        int spacePos2 = line.indexOf(' ', spacePos+1);
        String accId2;
        if( spacePos2 > 0 )
            accId2 = line.substring(spacePos+1, spacePos2);
        else
            accId2 = line.substring(spacePos+1);

        // for cyclic relationships, add relationships as synonyms
        if( Record.isCyclicRelationship(rec.getTerm().getOntologyId(), relName) ) {
            rec.addSynonym(relName+" "+accId2, "cyclic_relationship");
        }
        else {
            addRelationship(rec, accId2, rel, ontId, relName);
            if( rel.equals(Relation.NOT_SPECIFIED) ) {
                handleUnexpectedRelation(line);
            }
        }
    }

    void handleUnexpectedRelation(String line) {

        // sample unexpected relations:
        // is_conjugate_acid_of CHEBI:16926
        // has_quality SO:0000894 ! silenced_by_DNA_modification
        //
        // we will keep counts only of types of unexpected relations
        int pos = line.indexOf(' ');
        String relName = line.substring(0, pos);
        counters.increment("UNSPECIFIED_RELATION_"+relName);
    }

    private void addRelationship(Record rec, String accId, Relation rel, String ontId, String relName) {

        if( ontId.equals("EFO:") ) {
            if( accId.startsWith("efo:EFO_") ) {
                accId = "EFO:" + accId.substring(8);
            }
            else if( accId.startsWith("EFO:efo:EFO_") ) {
                accId = "EFO:" + accId.substring(12);
            }
        }

        // for EFO add 'EFO:' prefix to all terms coming from external ontologies
        if( !accId.startsWith(ontId) && ontId.equals("EFO:") ) {
            accId = ontId + accId;
        }

        // if true, add the relationship as synonym, not a dag
        boolean addSynonym = !ontId.equals("*") && !accId.startsWith(ontId);

        // add NCBITaxon relationships as a synonym
        if( accId.startsWith("EFO:NCBITaxon:") ) {
            accId = accId.substring(4);
            addSynonym = true;
        }

        if( ontId.equals("EFO:") ) {
            // for EFO, not-specified relationships load as synonyms: they cause cycles
            if( rel.equals(Relation.NOT_SPECIFIED) ) {
                addSynonym = true;
            }
        }

        // acyclic relationship: check if accession id refers to external ontology
        if( addSynonym ) {
            if( rel.equals(Relation.NOT_SPECIFIED) ) {
                rec.addSynonym(relName + " " + accId, "external_ontology");
            } else {
                rec.addSynonym(rel + " " + accId, "external_ontology");
            }
        }
        else {
            // acyclic relationship within same ontology; create relationship edge
            rec.addEdge(accId, rel);
        }
    }

    /// convert some ontology accession ids into meaningful names

    String convertRelName(String relName) {
        String relName2 = relationshipSubstitutions.get(relName);
        return relName2!=null ? relName2 : relName;
    }

    synchronized private void deleteDagsForOntology(String ontId, Date cutoffDate) throws Exception {
        logger.debug("deleting dags for " + ontId);

        // GO ontology is composed of 3 subontologies
        if( ontId.equals("GO") ) {
            deleteDagsForOntology("BP", cutoffDate);
            deleteDagsForOntology("CC", cutoffDate);
            deleteDagsForOntology("MF", cutoffDate);
            return;
        }

        if( !MalformedOboFiles.getInstance().isWellFormed(ontId) ) {
            logger.warn("*** dag deletion aborted for malformed obo file ***");
            return;
        }

        int rowsAffected = dao.deleteDags(ontId, cutoffDate);
        if( rowsAffected!=0 ) {
            logger.info(rowsAffected + " dags deleted for " + ontId);
            counters.add("DAG_EDGES_DELETED", rowsAffected);
        }
    }

    synchronized private void dumpInsertedDagsForOntology(String ontId, Date cutoffDate) throws Exception {

        // GO ontology is composed of 3 subontologies
        if( ontId.equals("GO") ) {
            dumpInsertedDagsForOntology("BP", cutoffDate);
            dumpInsertedDagsForOntology("CC", cutoffDate);
            dumpInsertedDagsForOntology("MF", cutoffDate);
            return;
        }

        int rowsAffected = dao.dumpInsertedDags(ontId, cutoffDate);
        if( rowsAffected!=0 ) {
            logger.info(rowsAffected + " dags inserted for " + ontId);
            counters.add("DAG_EDGES_INSERTED", rowsAffected);
        }
    }

    /**
     * some ontologies have multiple roots; we can create an artificial ontology root
     * having these multiple ontology roots underneath
     * @param ontId ontology id
     * @param records map of incoming records
     * @return map of accession ids for root term relationships
     */
    private Map<String, String> loadRootTerms(String ontId, Map<String, Record> records) throws Exception {

        // check if we have root terms given
        String rootTerms = getRootTerms().get(ontId);
        if( rootTerms==null )
            return null;

        // yes, we do: first accid is for new root, the rest are IS_A relations
        String[] accIds = rootTerms.split(",");
        if( accIds.length<2 )
            return null;

        // create root element
        Record rec = new Record();
        rec.getTerm().setOntologyId(ontId);
        rec.getTerm().setAccId(accIds[0].trim());
        rec.getTerm().setTerm(ontId + " ontology");
        //rec.getTerm().setComment("created by OntologyLoad pipeline to enforce single root for ontology");
        rec.getTerm().setCreatedBy("OntologyLoad pipeline");
        rec.getTerm().setCreationDate(new java.util.Date());
        records.put(rec.getTerm().getAccId(), rec);

        Map<String, String> rootTermRelations = new HashMap<>();
        // create subroot elements
        for( int i=1; i<accIds.length; i++ ) {
            rootTermRelations.put(accIds[i].trim(), rec.getTerm().getAccId());
        }
        return rootTermRelations;
    }

    private void loadCyclicRelationships(String fileName, String ontId) throws IOException {

        BufferedReader reader = openFile(fileName);

        String line;
        String id = "";
        while( (line=reader.readLine())!=null ) {
            // detect new term id
            if( line.startsWith("id: ") ) {
                // update term acc id
                id = line.substring(4).trim();
            }
            else if( line.startsWith("is_cyclic: ") ) {
                // check if is_cyclic is true
                String isCyclic = line.substring(11).trim();
                if( isCyclic.equals("true") ) {
                    Record.addCyclicRelationship(ontId, id);
                }
            }
        }

        reader.close();

        // hard-coded: for GO ontology has-part relationships could introduce cycles
        //   load them as synonyms
        if( ontId.equals("GO") ) {
            Record.addCyclicRelationship("GO", "has_part");
            Record.addCyclicRelationship("BP", "has_part");
            Record.addCyclicRelationship("CC", "has_part");
            Record.addCyclicRelationship("MF", "has_part");
        }
        else
        if( ontId.equals("EFO") ) {
            Record.addCyclicRelationship("EFO", "EFO:0006351"); // has_about_it
        }
        else
        if( ontId.equals("UBERON") ) {
            Record.addCyclicRelationship("UBERON", "mutually_spatially_disjoint_with");
        }
        else
        if( ontId.equals("CHEBI") ) {
            Record.addCyclicRelationship("CHEBI", "has_parent_hybride");
            Record.addCyclicRelationship("CHEBI", "is_conjugate_acid_of");
            Record.addCyclicRelationship("CHEBI", "is_conjugate_base_of");
            Record.addCyclicRelationship("CHEBI", "is_enantiomer_of");
            Record.addCyclicRelationship("CHEBI", "is_substituent_group_from");
            Record.addCyclicRelationship("CHEBI", "is_tautomer_of");
            Record.addCyclicRelationship("CHEBI", "has_functional_parent");
        }
    }

    /**
     * modify configuration so only single ontology could be processed
     * @param ontId ontology id
     */
    public void enforceSingleOntology(String ontId) {

        // sanity check
        if( ontId==null )
            return;

        // modify ontPrefixes map: only the selected ontology should stay
        Iterator<String> it = ontPrefixes.keySet().iterator();
        while( it.hasNext() ) {
            String curOntId = it.next();
            if( !curOntId.equalsIgnoreCase(ontId) ) {
                it.remove();
            }
        }
    }

    public List<TermXRef> parseXrefs(String txt) {

        String list;
        // per obo format specification:
        //     [<dbxref definition>, <dbxref definition>, ...]
        // <dbxref definition>:
        //     <dbxref name> "<dbxref description>" {optional-trailing-modifier}
        // examples:
        //     [GO:ma, GO:midori "Midori was drinking and came up with this", GO:john {namespace=johnsirrelevantdbxrefs}]
        int firstBracketPos = txt.indexOf('[');
        int lastBracketPos = txt.lastIndexOf(']');
        if( firstBracketPos<0 || lastBracketPos<0 || firstBracketPos+1>=lastBracketPos ) {
            list = txt.trim();
        } else {
            list = txt.substring(firstBracketPos+1, lastBracketPos).trim();
        }
        if( list.equals("[]") ) {
            return null;
        }

        // remove from the list all {optional-trailing-modifier} parts
        int pos1, pos2;
        for(;;) {
            pos1 = list.indexOf('{');
            if( pos1 <= 0 )
                break;

            pos2 = list.indexOf('}', pos1+1);
            if( pos2 <= pos1 )
                break;

            list = list.substring(0, pos1-1) + list.substring(pos2+1);
        }

        // break list into dbrefs definitions
        // comma followed by space is the separator
        // but escaped comma is not!
        List<String> defs = splitListIntoXRefs(list);
        List<TermXRef> xrefs = new ArrayList<>();
        for( String dbxref: defs ) {
            TermXRef xref = new TermXRef();

            // remove from the list all "<dbxref-description>" parts
            pos1 = dbxref.indexOf('\"');
            if( pos1 > 0 ) {
                pos2 = dbxref.indexOf('\"', pos1+1);
                if( pos2 > pos1 ) {
                    xref.setXrefDescription(dbxref.substring(pos1+1, pos2));
                    dbxref = dbxref.substring(0, pos1-1);
                }
            }

            xref.setXrefValue(dbxref.trim());
            xrefs.add(xref);
        }
        return xrefs;
    }

    /**
     * break list into dbrefs definitions;
     * comma followed by space is the separator, but escaped comma is not!
     * also handle properly xref descriptions containing ", "
     * <p>examples:<pre>
     * GO:ma
     * GO:midori \"Midori\"
     * GO:midori "Midori \, 2"
     * GO:ma, GO:midori \"Midori\", GO:xxx "Ernie, ma", GO:yyy "Ernie, ma, kota"
     * </pre>
     * @param txt text to be parsed into xref(s)
     * @return
     */
    List<String> splitListIntoXRefs(String txt) {

        // strip escape characters first
        if( txt.indexOf('\\')>=0 ) {
            StringBuilder buf = new StringBuilder(txt.length());
            for( int i=0; i<txt.length(); i++ ) {
                char c = txt.charAt(i);
                if( c=='\\' ) {
                    c = txt.charAt(++i);
                }
                buf.append(c);
            }
            txt = buf.toString();
        }

        List<String> xrefs = new ArrayList<>();
        int pos = -1;
        int start = 0;
        do {
            pos = txt.indexOf(", ", pos);
            if( pos>0 ) {
                // handle double quotes: if a comma is part of xref description it must be left there!
                // f.e. 'GO:midori "one, two"'
                //
                // count of double quotes between start and current pos must be divisible by two
                int dblQuoteCount = 0;
                for( int i=start; i<pos; i++ ) {
                    if( txt.charAt(i)=='\"' ) {
                        dblQuoteCount++;
                    }
                }
                if( dblQuoteCount%2==1 ) {
                    // we are in the middle of xref description
                    continue;
                }
                // break the word
                xrefs.add(txt.substring(start, pos).trim());
                start = pos + 2;
            }
        } while( pos++ >= 0);
        // add the last xref
        xrefs.add(txt.substring(start).trim());

        return xrefs;
    }

    void addAltIdSynonymIfValid(Record rec, String accId, String value) {
        // a valid ALT_ID synonym, has two parts separated by a colon or underscore
        // 1. 1+ upper case characters
        // 2. 1+ digits
        int colonPos = value.indexOf(':');
        if( colonPos <=0 || colonPos==value.length()-1 ) {
            colonPos = value.indexOf('_');
            if( colonPos <=0 || colonPos==value.length()-1 ) {
                return;
            }
        }
        String prefix = value.substring(0, colonPos);
        String suffix = value.substring(colonPos+1);
        if( !prefix.matches("[A-Z]+") || !suffix.matches("[0-9]+") ) {
            return; // invalid ALT_ID
        }

        String altId = prefix+":"+suffix;

        TermSynonym synonym = new TermSynonym();
        synonym.setName(altId);
        synonym.setType("alt_id");
        synonym.setTermAcc(accId);
        rec.addSynonym(synonym);
    }

    public Map<String, String> getOntPrefixes() {
        return ontPrefixes;
    }

    public void setOntPrefixes(Map<String, String> ontPrefixes) {
        this.ontPrefixes = ontPrefixes;
    }

    public OntologyDAO getDao() {
        return dao;
    }

    public void setDao(OntologyDAO dao) {
        this.dao = dao;
    }

    public void setRootTerms(Map<String, String> rootTerms) {
        this.rootTerms = rootTerms;
    }

    public Map<String, String> getRootTerms() {
        return rootTerms;
    }

    public void setPropertyValueSubstitutions(Map<String, String> propertyValueSubstitutions) {
        this.propertyValueSubstitutions = propertyValueSubstitutions;
    }

    public Map<String, String> getPropertyValueSubstitutions() {
        return propertyValueSubstitutions;
    }

    public void setOntologiesWithExactMatchSynonyms(List<String> ontologiesWithExactMatchSynonyms) {
        this.ontologiesWithExactMatchSynonyms = ontologiesWithExactMatchSynonyms;
    }

    public List<String> getOntologiesWithExactMatchSynonyms() {
        return ontologiesWithExactMatchSynonyms;
    }

    public void setIgnoredProperties(List<String> ignoredProperties) {
        this.ignoredProperties = ignoredProperties;
    }

    public List<String> getIgnoredProperties() {
        return ignoredProperties;
    }

    public void setPropertyToSynonym(List<String> propertyToSynonym) {
        this.propertyToSynonym = propertyToSynonym;
    }

    public List<String> getPropertyToSynonym() {
        return propertyToSynonym;
    }

    public void setSynonymPrefixSubstitutions(Map<String,String> synonymPrefixSubstitutions) {
        this.synonymPrefixSubstitutions = synonymPrefixSubstitutions;
    }

    public Map<String,String> getSynonymPrefixSubstitutions() {
        return synonymPrefixSubstitutions;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Map<String, String> getRelationshipSubstitutions() {
        return relationshipSubstitutions;
    }

    public void setRelationshipSubstitutions(Map<String, String> relationshipSubstitutions) {
        this.relationshipSubstitutions = relationshipSubstitutions;
    }
}
