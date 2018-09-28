#
APP=/home/rgddata/pipelines/OntologyLoad

#ontologies=( "CHEBI" )
#ontologies=( "MP" )
ontologies=( "XCO" "CMO" "PW" "VT" "RS" "MP" "RDO" "GO" "CHEBI" )
#ontologies=( "RDO" "XCO" "PW" "CMO" "VT" "RS" "MP" "GO" )

for ontology in "${ontologies[@]}"
do
    echo $APP/run_single.sh "$ontology" -filter=$1 -skip_downloads $@
    $APP/run_single.sh "$ontology" -filter=$1 -skip_downloads $@
done

