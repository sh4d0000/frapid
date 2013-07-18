import groovy.xml.StreamingMarkupBuilder
import java.nio.file.Paths 
import java.nio.file.Path 
import java.nio.file.Files

import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerFactory
import javax.xml.transform.OutputKeys

import static Frapid.*

class ProjectManager {

    protected static final String DEFAULT_COMPONENT_NAME = 'SampleComponent'
    protected static final String DEFAULT_JPG = 'default.jpg'
    protected static final String FRAPID_FLAG_FILE = '.frapid'
    protected static final String NAME_PLACEHOLDER = "_name_"
    protected static final String APP_NAME_PLACEHOLDER = "_appname_"
    def dirs, config
    def serviceLocator, scaffolder 

    def ProjectManager() {

        dirs = [COMPONENTS_DIR_NAME, LIB_DIR_NAME, CONFIG_DIR_NAME, TEMP_DIR_NAME, MEDIA_DIR_NAME,
                DIST_DIR_NAME, DOCS_DIR_NAME, MODEL_DIR_NAME];

        def frapidPath = System.getenv()["FRAPID_HOME"]
        config = new ConfigSlurper().parse( new File( "${frapidPath}/config.groovy" ).toURL() )
  
        serviceLocator = ServiceLocator.instance
       	scaffolder = serviceLocator.get SCAFFOLDER

    }

    def createProject( name, path = DEFAULT_PATH ) {

        def projectRootPath = Paths.get path, name
        def toCreate = [ projectRootPath ]

        toCreate += dirs.collect { projectRootPath.resolve it }

        serviceLocator.fileSystem {
            toCreate.each { createDir it, false }
        }

        def templatesDir = Paths.get config.frapid.templates
        def configDir = projectRootPath.resolve CONFIG_DIR_NAME
        def mediaDir = projectRootPath.resolve MEDIA_DIR_NAME

        // create routes.xml
        def file = serviceLocator.fileSystem { copy( templatesDir, configDir, ROUTES_FILE_NAME ).toFile()  }
        file.write( file.text.replaceAll(APP_NAME_PLACEHOLDER, name) )

        // create deploy.xml
        file = serviceLocator.fileSystem { copy( templatesDir, configDir, DEPLOY_FILE_NAME).toFile() }
        file.write( file.text.replaceAll(NAME_PLACEHOLDER, name) )

        // create config.xml .frapid and default.jpg
        serviceLocator.fileSystem {
            copy templatesDir, configDir, CONFIG_FILE_NAME
            copy templatesDir, projectRootPath, FRAPID_FLAG_FILE
            copy templatesDir, mediaDir, DEFAULT_JPG
        }

        scaffolder.generate( ScaffolderService.BUSINESS_COMPONENT, DEFAULT_COMPONENT_NAME, projectRootPath )

        return true
    }

    def rename( name, path = '.' ) {
        
        def projectRoot = getProjectRoot path

       	def deployer = serviceLocator.get DEPLOYER
        deployer.undeploy projectRoot  
        
        def deployXMLPath = projectRoot.resolve( CONFIG_DIR_NAME + File.separator + DEPLOYE_FILE_NAME )
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

        while ( Files.notExists( path.resolve( FRAPID_FLAG_FILE ) ) ) {
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
