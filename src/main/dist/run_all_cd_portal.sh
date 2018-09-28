# launch ontology loading sequentially for every ontology, except RDO
# if loading of any ontology fails, it won't affect loading of other ontologies
# that's why this script is preferred to run over run.sh script
#
APP=/home/rgddata/pipelines/OntologyLoad

ontologies=( "RDO" "XCO" "PW" "CMO" "VT" "RS" "MP" "GO" "CHEBI" )

for ontology in "${ontologies[@]}"
do
    $APP/run_single.sh "$ontology" -filter=DOID:1287 -skip_downloads -qc_thread_count=9 $@
done


