PROCESSING RULES (aka requirements)

1) term definitions
  if incoming term does not have a definition, but there is a term definition in RGD,
  then term definition in RGD will be used during loading
  REASON: a curator could enter a term definition into RGD if a term definition is missing;
      it will be unwise to undermine that effort by removing the term definition during loading

2) RS ontology synonyms QC
   if a synonym starts with 'RGD', it must be like 'RGD ID: ' (not like 'RGD:ID ' etc)
   this is to ensure that strain rgd ids are properly entered as RS term synonyms

3) strip comments from term names
  original line from HP.obo file:
      name: Increased serum insulin-like growth factor 1 {comment="HPO:probinson"}
  line after post-processing, to be used for loading into RGD
      name: Increased serum insulin-like growth factor 1

4) cycle detection
  by definition, an ontology must form a DAG (directed-acyclic-graph) and any loops (cycles) are not permitted;
  QC threads perform checking if the term being processed does not form cycles, and if yes, this is reported
  however, this could be a false alert, because after all terms are processed, post processing code is run
    and deleted all dags that had not been processed by the load process; thus these deleted dags could be the ones
    that were causing cycles; therefore it is recommended to reload the ontology with reported cycles once again
    to be sure that the errors were false positives only

5) synonyms for CHEBI ontology
   incoming synonyms like '0', '0.0', '234.433', '-2' are skipped from loading, as of March 16, 2017;
   they were messing up badly search results into OntoMate text mining tool

6) xrefs
   Definition xrefs are loaded into ONT_XREFs table.
   Term xrefs (obo line starting with 'xref: ') are loaded as synonyms of type 'xref'

API KEY

The data source for many ontologies is http://data.bioontology.org.
This source requires to provide a unique 'apikey'.
For security reasons we store the actual value of 'apikey' not in the code,
but in an external property file 'properties/api.key'.

OBSOLETE FEATURES

a) CTDMapper for RDO ontology
   we import disease ontology from CTD, and the term ids are MESH:xxx or OMIM:xxx ids;
   to map these to RDO ontology term, we use 'primary_id' synonyms of RDO terms;
   id incoming id cannot be matched to RDO term, we try to match 'alt_id's to RDO ontology terms

