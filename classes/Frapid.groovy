import java.nio.file.Paths 
import java.nio.file.Files
import groovy.xml.MarkupBuilder
import static java.nio.file.StandardCopyOption.*

class Frapid {

   def dirs, config
   
   def Frapid() {

      dirs = ["components", "lib", "config", "tmp"];

      def frapidPath = System.getenv()["FRAPID_HOME"]
      config = new ConfigSlurper().parse(new File("${frapidPath}/config.groovy").toURL())
   }

   def createProject( name, path = "." ) {

      def projectRoot = path + File.separator + name
      def toCreate = [ projectRoot ]

      toCreate += dirs.collect { projectRoot + File.separator + it } 
      toCreate.collect { Files.createDirectory( Paths.get( it ) ) }

      // create routes.xml
      def routesXml = Paths.get( config.frapid.templates + File.separator + "routes.xml" )
      def configDir = Paths.get( projectRoot + File.separator + "config/routes.xml" )
      def component = Files.copy( routesXml, configDir )
   

      generate( "business_component", "SampleComponent", projectRoot )

    
   }

   def generate( type, name, path = "." ) {

      if( type == "business_component" ) {

         def template = Paths.get( config.frapid.templates + File.separator + camelize(type) + ".php" )
         def destinationFolder = Paths.get( config.frapid.temp + File.separator + "${name}.php" )
	 def component = Files.copy( template, destinationFolder, REPLACE_EXISTING )
         destinationFolder = Paths.get( path + "/components/${name}.php" )
         def componentFile = component.toFile()

         componentFile.write( componentFile.text.replaceAll("_${type}_", name) )
	 Files.move( component, destinationFolder, REPLACE_EXISTING )

         /*
           TODO attualmente il comando è eseguibile solo dalla root di progetto
         */


      } else if( type == "front_controller" ) {

         def template = Paths.get( config.frapid.templates + File.separator + camelize(type) + ".php" )
         def destinationFolder = Paths.get( config.frapid.temp + File.separator + "${name}.php" )
	 def action = Files.copy( template, destinationFolder, REPLACE_EXISTING )
         destinationFolder = Paths.get( config.frapi.action+ File.separator + "${name}.php" )
         def actionFile = action.toFile()

         actionFile.write( actionFile.text.replaceAll("_${type}_", name) )
	 Files.move( action, destinationFolder, REPLACE_EXISTING )


      }

   }

   def deploy() {

      undeploy()
      Files.createDirectory( Paths.get( config.frapi.frapid ) ) 

      // crea actions.xml e action cloni
      def routes = new XmlSlurper().parse("config/routes.xml")
      def writer = new FileWriter(config.frapi.config + File.separator + "actions.xml")
      def xml = new MarkupBuilder( writer )
 
      def names = [];

      xml.'frapi-config' {
	actions {
	   routes.route.each {
 	      def _route = it

	      action {	
 	         def _name =  "${_route.component}".replace('#', '_') 
		 names << _name	

 	         name( _name )
		 route( _route.url )
		 description()
		 'public'(1)
		 enabled(1)
		 parameters()
	      }
           }
	  
        }
      }

     writer.close()

     names.each { generate( 'front_controller', it ) }


     def path = Paths.get( config.frapid.classes + File.separator + "Frapid.php"  )
     def destinationFolder = Paths.get( config.frapi.frapid + File.separator + "Frapid.php" )
     Files.copy( path, destinationFolder, REPLACE_EXISTING )

     path = Paths.get( "config/routes.xml"  )
     destinationFolder = Paths.get( config.frapi.frapid + File.separator + "routes.xml" )
     Files.copy( path, destinationFolder, REPLACE_EXISTING )

     new File("components").eachFileMatch( ~/.*\.php/ ) { component ->

        path = Paths.get( component.absolutePath )
        destinationFolder = Paths.get( config.frapi.frapid + File.separator + component.name )
	Files.copy( path, destinationFolder, REPLACE_EXISTING )

     }
     


   }

   def undeploy() {

      def frapidDir = new File( config.frapi.frapid )
      
      if( frapidDir.exists()  ) {

         frapidDir.eachFile { component ->
             Files.delete Paths.get( component.absolutePath )
         } 


         Files.delete( Paths.get( config.frapi.frapid )  )

      }


      new File( config.frapi.action ).eachFileMatch( ~/.*\.php/ ) { component ->
         Files.delete Paths.get( component.absolutePath )
      } 

   }


def camelize(String self) {
   self.split("_").collect() { it.substring(0, 1).toUpperCase() + it.substring(1, it.length()) }.join()
}


}
