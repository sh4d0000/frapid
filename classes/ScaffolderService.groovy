import java.nio.file.Paths 
import java.nio.file.Path 
import java.nio.file.Files

import groovy.xml.MarkupBuilder

class ScaffolderService {

    def config, serviceLocator, projectManager

    def ScaffolderService() {

       def frapidPath = System.getenv()["FRAPID_HOME"]
       config = new ConfigSlurper().parse( new File( "${frapidPath}/config.groovy" ).toURL() )
  
       serviceLocator = ServiceLocator.instance

    }
  

    def generate( type, name, projectPath = "." ) {

        def projectManager = serviceLocator.get 'projectManager'
        def projectRoot = projectManager.getProjectRoot projectPath

        def app = new XmlSlurper().parse( projectRoot.resolve( "config/deploy.xml").toString() )
                
        def templatesDir = Paths.get config.frapid.templates 
        def componentsDir = projectRoot.resolve( "components" )
        
        if( type == "business_component" ) {

            def file = serviceLocator.fileSystem { copy( templatesDir, componentsDir, camelize(type) + ".php").toFile() }
            file.write( file.text.replaceAll("_${type}_", name) )
            file.write( file.text.replaceAll("_namespace_", app.name.toString() ) )
            file.renameTo( componentsDir.toString() + File.separator + "${name}.php" )
                        
        } 

    }

    def generateRoutes( projectPath = '.' ) {
        
        def projectManager = serviceLocator.get 'projectManager'
        def projectRoot = projectManager.getProjectRoot projectPath
        
        def app = new XmlSlurper().parse( projectRoot.resolve( "config/deploy.xml").toString() )
        
        def routesXml = projectRoot.resolve "config/routes.xml"
        Files.deleteIfExists routesXml
        
        def routeList = []
        
        projectRoot.resolve("components").toFile().eachFileMatch( ~/.*\.php/ ) { component ->
	    def componentName = component.name.split("\\.")[0]
            
            def phpDocOpen = false
            def phpDocClose = false
            
            def phpDocBlocks = []
            def newBlock = null
           
            
            component.eachLine() {
                
                if( it.contains("/*") && !phpDocOpen ) {
                    
                    phpDocOpen = true
                    newBlock = []
                    newBlock << it
                    
                } else if ( !it.contains("*/") && phpDocOpen ) { 
                    
                    newBlock << it
                    
                } else if ( it.contains("*/") && phpDocOpen ) { 
                    
                    phpDocOpen = false
                    phpDocClose = true
                    newBlock << it
                    
                } else if ( phpDocClose ) { 
                    
                    // recupera la riga successiva la chiusura del blocco quindi la dichiarazione della funzione
                    phpDocClose = false
                    newBlock << it
                    phpDocBlocks << newBlock
                                                        
                }
                
            }
            
            phpDocBlocks.each() { docBlock ->
            
                def route = [component:componentName]
                
                docBlock.each { line ->
                    if( line.contains("@frapid-url") ) {
                        route.url = line.split("@frapid-url")[1].trim()
                    } else if( line.contains("@frapid-method") ) {
                        route.httpMethod = line.split("@frapid-method")[1].trim()
                    }
                }
                
                route.function = docBlock.last().split("function")[1].split("\\(")[0].trim()
                
                routeList << route
                
            }
            
        }
        
        // Creazione routes.xml
        routesXml.toFile().withWriter { writer ->
            
            def xml = new MarkupBuilder(writer)
            xml."routes-config"() {
                
                root( app.name )                
                routes() {
                    routeList.each { element ->
                        route() {
                            url( element.url )
                            component( "$element.component#$element.function" ) 
                            method( element.httpMethod )
                        }
                    }
                } 
                
            }
                                   
        }
        
        
        
    }

    def camelize(String self) {
        self.split("_").collect() { it.substring(0, 1).toUpperCase() + it.substring(1, it.length()) }.join()
    }

}
