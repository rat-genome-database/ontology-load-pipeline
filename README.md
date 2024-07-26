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

GRAPH CYCLES

Ontology terms are loaded into a DAG structure, meaning, cycles are not allowed. Therefore during loading,
after a term is processed, checks are performed to see if cycles has been created. If yes, they are being reported.
However, when entire ontology has been loaded, there should not be any cycles. If they are, they must be removed,
or that will break some pipelines and tools in RGD.

At any time, script 'check_for_cycles.sh <ONT_ID>' could be run to see if there are any cycles. If there are,
just pick any term and run these queries (modifying value of LEVEL parameter) to see a cycle in the path:

<pre>
select sys_connect_by_path(child_term_acc,'/') p
from ont_dag
start with parent_term_acc='UBERON:0003104'
connect by  prior child_term_acc=parent_term_acc
 and level<31
order by length(p) desc

select sys_connect_by_path(parent_term_acc,'/') p
from ont_dag
start with child_term_acc='UBERON:0003104'
connect by  prior parent_term_acc=child_term_acc
 and level<31
order by length(p) desc
</pre>
then drop the offending relationship from ONT_DAG table

NEW FEATURES

1) as of July 26, 2024 we have: 
    SSSOM TSV generator of ontology mappings for EFO->CMO, EFO->MP, EFO->HP, EFO->RDO and EFO->VT

OBSOLETE FEATURES

a) CTDMapper for RDO ontology
   we import disease ontology from CTD, and the term ids are MESH:xxx or OMIM:xxx ids;
   to map these to RDO ontology term, we use 'primary_id' synonyms of RDO terms;
   id incoming id cannot be matched to RDO term, we try to match 'alt_id's to RDO ontology terms

