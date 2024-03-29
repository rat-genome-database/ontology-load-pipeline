# launch ontology loading sequentially for every ontology, except RDO
#
APP=/home/rgddata/pipelines/OntologyLoad

ontologies=( "XCO" "MMO" "NBO" "MI" "SO" "CL" "PW" "ZFA" "EFO" "CMO" "VT" "MA" "RS" "MP" "HP" "UBERON" "GO" "CHEBI" )

for ontology in "${ontologies[@]}"; do
    #update ontology and term stats
    $APP/run_single.sh "$ontology" $1 $2 $3 $4 $5 $6 $7 $8
    echo ""
done
