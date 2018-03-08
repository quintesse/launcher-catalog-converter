# launcher-catalog-converter-2

Quick &amp; dirty app to convert from the old-style booster catalog format (18-24) to the new one (25+)

## Build

   $ mvn clean package
   
## Run

    $ java -jar target/catalog-converter-2-0.0.1-SNAPSHOT-jar-with-dependencies.jar
   
The above command will give you the "help" (as far as you can call it that).

For converting the RHOAR catalog you do:

    $ java -jar target/catalog-converter-2-0.0.1-SNAPSHOT-jar-with-dependencies.jar converted-catalog "" master
   
