import groovy.xml.StreamingMarkupBuilder
import java.nio.file.Paths 
import java.nio.file.Path 
import java.nio.file.Files

import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerFactory
import javax.xml.transform.OutputKeys

class ProjectManager {

    def dirs, config
    def serviceLocator, scaffolder 

    def ProjectManager() {

        dirs = ["components", "lib", "config", "tmp", "media", "dist", "docs"];

        def frapidPath = System.getenv()["FRAPID_HOME"]
        config = new ConfigSlurper().parse( new File( "${frapidPath}/config.groovy" ).toURL() )
  
        serviceLocator = ServiceLocator.instance
       	scaffolder = serviceLocator.get 'scaffolder'

    }

    def createProject( name, path = ".", force = false ) {

        if( !force && !checkName(name) ) {
            return false
        }

        def projectRootPath = Paths.get path, name
        def toCreate = [ projectRootPath ]

        toCreate += dirs.collect { projectRootPath.resolve it }

        serviceLocator.fileSystem {
            toCreate.each { createDir it, false }
        }

        def templatesDir = Paths.get config.frapid.templates
        def configDir = projectRootPath.resolve 'config'
        def mediaDir = projectRootPath.resolve 'media'

        // create routes.xml
        def file = serviceLocator.fileSystem { copy( templatesDir, configDir, 'routes.xml' ).toFile()  }
        file.write( file.text.replaceAll("_appname_", name) )

        // create deploy.xml
        file = serviceLocator.fileSystem { copy( templatesDir, configDir, 'deploy.xml').toFile() }
        file.write( file.text.replaceAll("_name_", name) )

        // create config.xml .frapid and default.jpg
        serviceLocator.fileSystem {
            copy templatesDir, configDir, 'config.xml'
            copy templatesDir, projectRootPath, '.frapid'
            copy templatesDir, mediaDir, 'default.jpg'
        }

        scaffolder.generate( "business_component", "SampleComponent", projectRootPath )

        def keyDir = Paths.get config.frapid.keyDir
        if( Files.notExists( keyDir.resolve( "public.key" ) ) || Files.notExists( keyDir.resolve( "private.key") ) ) {
            digitalSignature.generateKeys()
        }

        return true

    }

    def checkName( name = null, path = "."  ) {
        
        if( !name ) {
            def projectRoot = getProjectRoot path
            def app = new XmlSlurper().parse( projectRoot.resolve( "config/deploy.xml").toString() )
            name = app.name
        }
        
        def nameAvailable = false
        
        def uri = config.envs.prod.uri.split(':')
        def s = new Socket( uri[0], uri[1].toInteger() );
        
        s.withStreams { input, output ->
            
            output << "checkName $name\n"

            def reader = input.newReader()
            def buffer = reader.readLine()
            
            nameAvailable = buffer=='ok'? true :false
                                     
            buffer = reader.readLine()
            if(buffer == 'bye') {
                //println "Terminato"
            }
        }
        
        return nameAvailable
    }
    
    def isProjectNameAvailable( name ) {
        
        def frapiConfPath = Paths.get( config.frapid.home, config.frapid.frapiConfigFile )
        
        def frapiConf = new XmlSlurper().parse( frapiConfPath.toString() )
        def db = frapiConf.database
        
        def driverURL = new File( config.frapid.mysqlDriver ).toURL()
        this.getClass().classLoader.rootLoader.addURL( driverURL )
        
        def sql = groovy.sql.Sql.newInstance("jdbc:mysql://$db.url", db.username.toString(), db.password.toString() )
        
        def answer = 0
        sql.eachRow("select count(*) from api where name = $name") { row ->
            answer = row[0]
        }
        
        return answer == 0? true : false     
        
    }
    
    def rename( name, path = '.' ) {
        
        def projectRoot = getProjectRoot path

       	def deployer = serviceLocator.get 'deployer'
        deployer.undeploy projectRoot  
        
        def deployXMLPath = projectRoot.resolve("config/deploy.xml")
        def deployXML = new XmlSlurper().parse( deployXMLPath.toString() )
        
        deployXML.name = "$name"

        def outputBuilder = new StreamingMarkupBuilder()
        String result = indentXml( outputBuilder.bind{ mkp.yield deployXML } )
        
        new File(deployXMLPath.toString()).text = result
        
        scaffolder.generateRoutes()
        projectRoot.toFile().renameTo( projectRoot.getParent().resolve("$name").toString() ) 
        
    }
    
    def getProjectRoot( path ) {
      
        path = Path.class.isInstance(path)? path : Paths.get( path.toString() ).toRealPath()
                
        if( !Files.isDirectory( path ) ) throw new IllegalArgumentException( "Invalid Path, not a directory " )

        while ( Files.notExists( path.resolve(".frapid") ) ) {
            if( !path.parent ) throw new IllegalArgumentException( "No Frapid project found" )
            path = path.parent
        }

        return path
    }

    def String indentXml(xml) {
        def factory = TransformerFactory.newInstance()
        factory.setAttribute("indent-number", 3);

        Transformer transformer = factory.newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, 'yes')
        StreamResult result = new StreamResult(new StringWriter())
        transformer.transform(new StreamSource(new ByteArrayInputStream(xml.toString().bytes)), result)
        return result.writer.toString()
    }

}
