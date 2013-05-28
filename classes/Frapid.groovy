import java.nio.file.Paths 
//import java.nio.file.Path 
import java.nio.file.Files

// import java.security.*

// import java.math.BigInteger;
import java.util.Date;
//import java.io.IOException


import java.util.concurrent.ExecutionException

class Frapid {

    def config, serviceLocator
    def scaffolder, digitalSignature, projectManager, deployer
   
    def Frapid() {

        def frapidPath = System.getenv()["FRAPID_HOME"]
        config = new ConfigSlurper().parse( new File( "${frapidPath}/config.groovy" ).toURL() )

        serviceLocator = ServiceLocator.instance 
        deployer = serviceLocator.get 'deployer'
        projectManager = serviceLocator.get 'projectManager'
        scaffolder = serviceLocator.get 'scaffolder'
        digitalSignature = serviceLocator.get 'digitalSignature'

    }

    def createProject( name, path = "." ) {
        projectManager.createProject name, path
    }

    def checkName( name = null, path = "."  ) {
        projectManager.checkName name, path
    }
    
    def isProjectNameAvailable( name ) {
        projectManager.isProjectNameAvailable name
    }
    
    def rename( name, path = '.' ) {
        projectManager.rename name, path
    }
    
    def generate( type, name, path = "." ) {
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

    def deploy( projectPath, environment = 'dev' ) {
	deployer.deploy projectPath, environment
    }

    def undeploy( projectPath, environment = 'dev' ) {
	deployer.undeploy projectPath, environment
    }
    
    def pack( path = ".", sign = true ) {
	deployer.pack path, sign
    }

    def unpack( file, path = "." ) {
	deployer.unpack file, path
    }

    def publish( pack, env = 'dev', destination = '/tmp/proj/'){
	deployer.publish pack, env, destination
    }

    def submit( username, projectPath = '.', env = 'prod' ) {
	deployer.submit username, projectPath, env
    }

    def getUserIdBy( username, sql ) {
        
        def userId
        sql.eachRow("SELECT uid FROM users WHERE name = $username") { row ->
            userId = row[0]
        }
        
        return userId
        
    }

    def config( environment = 'dev') {

        def FRAPI_PATH = System.getenv()["FRAPI_PATH"]

        if( !Files.exists( Paths.get(FRAPI_PATH) ) ) {

		def apiPort=10220, adminPort=10221
		installFrapi(adminPort, apiPort)
		println "-------------------------------"
		println "frapi and nginx installed"
		println "frapi API responds from port $apiPort"
		println "Nginx *MUST* be started manually"
	}
        
        def frapiConf = config.envs."$environment".frapi
        
        // sostituisci main controller
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

        
        def p = serviceLocator.fileSystem { copy config.frapid.home, frapiConf.frapid, config.frapid.frapiConfigFile }

    }

    def unconfig( environment = 'dev') {

        def workingDir = new File(config.envs."$environment".frapi.home)
        "git add .".execute(null, workingDir).waitFor()
        "git reset --hard".execute( null, workingDir )
        
        def frapidDir = Paths.get( config.envs."$environment".frapi.frapid ).toFile()
        frapidDir.deleteDir()

    }

   def installFrapi(adminPort, apiPort) {
		println 'installing frapi'

		def HOME = System.getenv()["HOME"]
		def WORKSPACE = "$HOME"
		def ADMIN_FRAPI_PORT = adminPort
		def API_FRAPI_PORT = apiPort

		def commands = """mkdir $WORKSPACE/opt
mkdir $WORKSPACE/dist"""
		def exitCode = execute commands

		println "------------EXIT CODE-----------"
		println "$exitCode"
		println "--------------------------------"

		commands = """wget http://nginx.org/download/nginx-1.2.1.tar.gz
tar -xvzf nginx-1.2.1.tar.gz
rm nginx-1.2.1.tar.gz"""

		exitCode = execute commands, "$WORKSPACE/dist"

		println "------------EXIT CODE-----------"
		println "$exitCode"
		println "--------------------------------"

		commands = """mkdir $WORKSPACE/opt/nginx-1.2.1
./configure --prefix=$WORKSPACE/opt/nginx-1.2.1
make 2>&1 | tee make.log
make install 2>&1 | tee install.log"""

		exitCode = execute commands, "$WORKSPACE/dist/nginx-1.2.1"

		println "------------EXIT CODE-----------"
		println "$exitCode"
		println "--------------------------------"

		commands = """git clone git://github.com/frapi/frapi.git"""

		exitCode = execute commands, "$WORKSPACE/opt"

		println "------------EXIT CODE-----------"
		println "$exitCode"
		println "--------------------------------"

		def FRAPI_PATH = "$WORKSPACE/opt/frapi/"

		commands = """chmod ugo+x setup.sh
./setup.sh
mkdir -p $FRAPI_PATH/log/nginx/admin.frapi
mkdir -p $FRAPI_PATH/log/nginx/api.frapi"""

		exitCode = execute commands, FRAPI_PATH

		println "------------EXIT CODE-----------"
		println "$exitCode"
		println "--------------------------------"

		def file = new File("$WORKSPACE/opt/nginx-1.2.1/conf/nginx.conf")
		file.delete()
		file.createNewFile()  

		file << """worker_processes  1;

events {
    worker_connections  1024;
}


http {
    include       mime.types;
    default_type  application/octet-stream;

    sendfile        on;

    keepalive_timeout  65;

    server {
      listen   $ADMIN_FRAPI_PORT;
      server_name  admin.frapi;
      access_log  $FRAPI_PATH/log/nginx/admin.frapi/access.log;

      root   $FRAPI_PATH/src/frapi/admin/public;
      index index.php;

      location / {
        try_files \$uri \$uri/ @api;
      }

      location @api {
        rewrite ^/(.*)\$ /index.php?\$1 last;
      }

      location ~ \\.php\$ {
        fastcgi_pass   127.0.0.1:9000;
        fastcgi_index  index.php;
        fastcgi_param  SCRIPT_FILENAME  $FRAPI_PATH/src/frapi/admin/public/\$fastcgi_script_name;
        include fastcgi_params;
      }
   }

server {
    listen   $API_FRAPI_PORT;
    server_name  api.frapi;
    access_log  $FRAPI_PATH/log/nginx/api.frapi/access.log;

    root     $FRAPI_PATH/src/frapi/public;
    index    index.php;

    location ~* ^.+\\.(jpg|js|jpeg|png|ico|gif|js|css|swf)\$ {
        expires 24h;
    }

    location / {
        try_files \$uri \$uri/ @api;
    }

    location @api {
        rewrite  ^/(.*)\$  /index.php?\$1  last;
    }

    location ~ ^/.*\\.php\$ {
        fastcgi_pass   127.0.0.1:9000;
        fastcgi_index  index.php;
        fastcgi_param  SCRIPT_FILENAME  $FRAPI_PATH/src/frapi/public/\$fastcgi_script_name;
        include fastcgi_params;
    }
}
}
"""

		file = new File("$WORKSPACE/opt/nginx-1.2.1/conf/fastcgi.conf")
		file.delete()
		file.createNewFile()  

		file << """fastcgi_param SCRIPT_NAME \$fastcgi_script_name;
fastcgi_param  QUERY_STRING       \$query_string;
fastcgi_param  REQUEST_METHOD     \$request_method;
fastcgi_param  CONTENT_TYPE       \$content_type;
fastcgi_param  CONTENT_LENGTH     \$content_length;

fastcgi_param  SCRIPT_NAME        \$fastcgi_script_name;
fastcgi_param  REQUEST_URI        \$request_uri;
fastcgi_param  DOCUMENT_URI       \$document_uri;
fastcgi_param  DOCUMENT_ROOT      \$document_root;
fastcgi_param  SERVER_PROTOCOL    \$server_protocol;
fastcgi_param  HTTPS              \$https if_not_empty;

fastcgi_param  GATEWAY_INTERFACE  CGI/1.1;
fastcgi_param  SERVER_SOFTWARE    nginx/\$nginx_version;

fastcgi_param  REMOTE_ADDR        \$remote_addr;
fastcgi_param  REMOTE_PORT        \$remote_port;
fastcgi_param  SERVER_ADDR        \$server_addr;
fastcgi_param  SERVER_PORT        \$server_port;
fastcgi_param  SERVER_NAME        \$server_name;"""


	}


	def execute( commands, workingDir = System.properties.'user.dir' ) {

		workingDir = new File( workingDir )
		commands.eachLine() { executeOnShell it, workingDir }
	}

	def executeOnShell( command, workingDir) {

		println "executing command: $command"

		def process = new ProcessBuilder(addShellPrefix(command))
				.directory(workingDir)
				.redirectErrorStream(true)
				.start()

		process.inputStream.eachLine {println it}
		process.waitFor()

		def exitValue = process.exitValue()

		if( exitValue != 0 ) {
			throw new ExecutionException ( "Something gone wrong. Contact system administrator." )
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
