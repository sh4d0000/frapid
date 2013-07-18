import java.nio.file.Paths 
//import java.nio.file.Path 
import java.nio.file.Files

// import java.security.*

// import java.math.BigInteger;
import java.util.Date;
//import java.io.IOException


import java.util.concurrent.ExecutionException

class Frapid {

    protected static final String PROJECT_MANAGER = 'projectManager'
    protected static final String SCAFFOLDER = 'scaffolder'
    protected static final String DIGITAL_SIGNATURE = 'digitalSignature'
    protected static final String COMPONENTS_DIR_NAME = 'components'
    protected static final String ROUTES_FILE_NAME = 'routes.xml'
    protected static final String CONFIG_FILE_NAME = 'config.xml'
    protected static final String LIB_DIR_NAME = 'lib'
    protected static final String CONFIG_DIR_NAME = 'config'
    protected static final String MODEL_DIR_NAME = 'model'
    protected static final String TEMP_DIR_NAME = 'tmp'
    protected static final String DIST_DIR_NAME = 'dist'
    protected static final String MEDIA_DIR_NAME = 'media'
    protected static final String DOCS_DIR_NAME = 'docs'
    protected static final String DEPLOY_FILE_NAME = 'deploy.xml'
    protected static final String DEPLOYER = 'deployer'
    protected static final String DEFAULT_PATH = "."
    protected static final String DEV_ENV = 'dev'
    protected static final String REMOTE_ENV = 'remote'

    def config, serviceLocator
    def scaffolder, digitalSignature, projectManager, deployer

    def Frapid() {

        def frapidPath = System.getenv()["FRAPID_HOME"]
        config = new ConfigSlurper().parse( new File( "${frapidPath}/config.groovy" ).toURL() )

        serviceLocator = ServiceLocator.instance 
        deployer = serviceLocator.get DEPLOYER
        projectManager = serviceLocator.get PROJECT_MANAGER
        scaffolder = serviceLocator.get SCAFFOLDER
        digitalSignature = serviceLocator.get DIGITAL_SIGNATURE

    }

    def createProject( name, path = DEFAULT_PATH) {
        projectManager.createProject name, path
    }

    def checkName( name = null, path = DEFAULT_PATH  ) {
        projectManager.checkName name, path
    }
    
    def isProjectNameAvailable( name ) {
        projectManager.isProjectNameAvailable name
    }
    
    def rename( name, path = '.' ) {
        projectManager.rename name, path
    }
    
    def generate( type, name, path = DEFAULT_PATH ) {
        scaffolder.generate( type, name, path )
    }
    
    def generateDoc( projectPath ) {
        scaffolder.generateDoc projectPath 
    }
    
    def generateRoutes( projectPath = '.' ) {
        scaffolder.generateRoutes projectPath
    }

    def generateKeys() {
	digitalSignature.generateKeys()
    }

    def verifyPack( pack, publicKey = null, signature = null ) {
        digitalSignature.verifyPack pack, publicKey, signature
    }

    def deploy( projectPath, environment = DEV_ENV ) {
	deployer.deploy projectPath, environment
    }

    def undeploy( projectPath, environment = DEV_ENV ) {
	deployer.undeploy projectPath, environment
    }

    def publish( pack, env = 'dev', destination = '/tmp/proj/'){
        deployer.publish pack, env, destination
    }

    def pack( path = DEFAULT_PATH, sign = true ) {
	deployer.pack path, sign
    }

    def unpack( file, path = DEFAULT_PATH ) {
	deployer.unpack file, path
    }

    def config( environment = DEV_ENV) {

        def frapiConf = config.envs."$environment".frapi
        
        def templatesDir = Paths.get config.frapid.templates
        serviceLocator.fileSystem {
            copy templatesDir, frapiConf.main_controller, 'Main.php'

            // moving front controller e actions.xml
            copy templatesDir, frapiConf.action, "Frontcontroller.php"
            copy templatesDir, frapiConf.config, "actions.xml"
        }



        Paths.get( frapiConf.custom, "AllFiles.php" ).toFile() << '''
$it = new RecursiveDirectoryIterator( CUSTOM_PATH . DIRECTORY_SEPARATOR . 'frapid' ); 

foreach (new RecursiveIteratorIterator( $it ) as $fileInfo) {
  if($fileInfo->getExtension() == "php") {
     require_once $fileInfo->getPathname();
  }
}
'''

        // creating Frapid dir in Frapi
        serviceLocator.fileSystem {
            createDir frapiConf.frapid
            copy config.frapid.classes, frapiConf.frapid , "Frapid.php"
        }

    }

    def unconfig( environment = 'dev') {

        def workingDir = new File(config.envs."$environment".frapi.home)
        "git add .".execute(null, workingDir).waitFor()
        "git reset --hard".execute( null, workingDir )
        
        def frapidDir = Paths.get( config.envs."$environment".frapi.frapid ).toFile()
        frapidDir.deleteDir()

    }

   	def execute( commands, workingDir = System.properties.'user.dir' ) {

		workingDir = new File( workingDir )
		commands.eachLine() { executeOnShell it, workingDir }
	}

	def executeOnShell( command, workingDir) {

		def process = new ProcessBuilder(addShellPrefix(command))
				.directory(workingDir)
				.redirectErrorStream(true)
				.start()

		process.inputStream.eachLine {println it}
		process.waitFor()

		def exitValue = process.exitValue()

		if( exitValue != 0 ) {
			throw new ExecutionException ( "Something went wrong. Contact system administrator." )
		}

		return exitValue
	}

	def addShellPrefix(String command) {

		def commandArray = new String[3]
		commandArray[0] = "sh"
		commandArray[1] = "-c"
		commandArray[2] = command

		return commandArray
	}

}
