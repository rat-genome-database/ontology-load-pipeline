package edu.mcw.rgd.dataload.ontologies.test;

/*
import org.incenp.obofoundry.sssom.TSVReader;
import org.incenp.obofoundry.sssom.SSSOMFormatException;
import org.incenp.obofoundry.sssom.model.MappingSet;
*/
import java.io.IOException;

public class SSSOMValidator {

    public void run( String fileName ) throws Exception {

        System.out.println("not implemented here");

        /* working code -- dependencies are so massive we moved the validator to separate project
        try {
            TSVReader reader = new TSVReader(fileName);
            MappingSet ms = reader.read();

            System.out.println("=== OK! Mappings read: "+ms.getMappings().size());
        } catch (IOException ioe) {
            throw new Exception("A non-SSSOM I/O error occured");
        } catch (SSSOMFormatException sfe) {
            throw new Exception("Invalid SSSOM data");
        }
        */
    }
}
