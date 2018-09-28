#load disease ontology RDO
APP=/home/rgddata/pipelines/OntologyLoad
$APP/run_single.sh RDO
$APP/generate_obo_file.sh RDO
