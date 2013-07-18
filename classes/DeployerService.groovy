import java.security.*
import java.nio.file.Paths
import java.nio.file.Files

import java.nio.file.attribute.PosixFilePermissions
import static Frapid.*
import static DigitalSignatureService.*

class DeployerService {

    private static final String DEFAULT_COMPRESSION = 'gzip'

    def config, serviceLocator, scaffolder, projectManager, digitalSignature

    def DeployerService() {

        def frapidPath = System.getenv()["FRAPID_HOME"]
        config = new ConfigSlurper().parse(new File("${frapidPath}/config.groovy").toURL())

        serviceLocator = ServiceLocator.instance
        projectManager = serviceLocator.get PROJECT_MANAGER
        scaffolder = serviceLocator.get SCAFFOLDER
        digitalSignature = serviceLocator.get DIGITAL_SIGNATURE

    }

    def deploy(projectPath, environment = DEV_ENV) {

        if (config.envs."$environment".type == REMOTE_ENV) {
            return remoteDeploy(projectPath, environment)
        }

        def projectRoot = projectManager.getProjectRoot projectPath
        def configDir = projectRoot.resolve CONFIG_DIR_NAME
        undeploy projectRoot

        scaffolder.generateRoutes projectRoot

        def app = new XmlSlurper().parse(configDir.resolve(DEPLOY_FILE_NAME).toString())
        def deployDir

        serviceLocator.fileSystem {

            deployDir = createDir(config.envs."$environment".frapi.frapid + File.separator + app.name)
            def componentsDeploy = createDir(deployDir.resolve(COMPONENTS_DIR_NAME))

            copy configDir, deployDir, ROUTES_FILE_NAME, CONFIG_FILE_NAME
            def libDeploy = createDir deployDir.resolve(LIB_DIR_NAME)
            def modelDeploy = createDir deployDir.resolve(MODEL_DIR_NAME)


            projectRoot.resolve(LIB_DIR_NAME).toFile().eachFileMatch(~/.*\.php/) { lib ->
                copy lib.absolutePath, libDeploy.resolve(lib.name)
            }

            projectRoot.resolve(COMPONENTS_DIR_NAME).toFile().eachFileMatch(~/.*\.php/) { component ->
                copy component.absolutePath, componentsDeploy.resolve(component.name)
            }

            projectRoot.resolve(MODEL_DIR_NAME).toFile().eachFileMatch(~/.*\.php/) { model ->
                copy model.absolutePath, modelDeploy.resolve(model.name)
            }

        }

        return deployDir

    }

    def remoteDeploy(projectPath, env) {

        def projectRoot = projectManager.getProjectRoot projectPath
        def app = new XmlSlurper().parse(projectRoot.resolve(CONFIG_DIR_NAME + File.separator + DEPLOY_FILE_NAME).toString())
        pack(projectPath, false)

        def uri = config.envs."$env".uri.split(':')
        def s = new Socket(uri[0], uri[1].toInteger());

        s.withStreams { input, output ->

            def packPath = projectRoot.resolve("dist/${app.name}_api.tar.gz")
            def sizePack = Files.size(packPath)

            output << "publish dev ${app.name}_api.tar.gz $sizePack\n"

            def reader = input.newReader()
            def buffer = reader.readLine()
            if (buffer == 'ok') {
                sendFile(packPath, input, output)
            }

            buffer = reader.readLine()
            if (buffer == 'bye') {
                //println "terminated"
            }
        }

        return true
    }

    def sendFile(path, input, output) {

        output.write(path.toFile().bytes)
        output.flush();

    }

    def undeploy(projectPath, environment = DEV_ENV) {

        def projectRoot = projectManager.getProjectRoot projectPath

        def app = new XmlSlurper().parse(projectRoot.resolve(CONFIG_DIR_NAME + File.separator + DEPLOY_FILE_NAME).toString())
        def deployDir = Paths.get(config.envs."$environment".frapi.frapid, (String) app.name).toFile()

        deployDir.deleteDir()

    }

    def pack(path = ".", sign = true) {

        path = projectManager.getProjectRoot path

        def app = new XmlSlurper().parse(path.resolve(CONFIG_DIR_NAME + File.separator + DEPLOY_FILE_NAME).toString())
        def ant = new AntBuilder()

        def projectPath = path.resolve("${TEMP_DIR_NAME + File.separator + app.name}.tar.gz")
        def sigPath = path.resolve("${TEMP_DIR_NAME + File.separator + app.name}.sig")

        Files.deleteIfExists projectPath

        ant.tar(destFile: projectPath, compression: DEFAULT_COMPRESSION) {
            tarfileset(dir: path, prefix: app.name)
        }

        if (sign) {
            // digitally sign data
            def privKeyPath = Paths.get config.frapid.keyDir, DigitalSignatureService.PRIVATE_KEY_FILE_NAME

            def privKey = digitalSignature.getPrivateKey(privKeyPath.toFile())

            Signature dsa = Signature.getInstance(DigitalSignatureService.SHA1WITH_RSA);
            dsa.initSign(privKey);

            FileInputStream fis = new FileInputStream(projectPath.toFile());
            BufferedInputStream inputStream = new BufferedInputStream(fis);
            byte[] buffer = new byte[1024];
            int len;
            while ((len = inputStream.read(buffer)) >= 0) {
                dsa.update(buffer, 0, len);
            };
            inputStream.close();

            byte[] realSig = dsa.sign();

            Files.write(sigPath, realSig)
        }

        def archivePath = path.resolve("dist/${app.name}_api.tar.gz")
        Files.deleteIfExists archivePath

        ant.tar(destFile: archivePath, compression: DEFAULT_COMPRESSION, basedir: path.resolve(TEMP_DIR_NAME))

        // deleting tmp files
        Files.delete projectPath
        Files.deleteIfExists sigPath
    }

    def unpack(file, path = ".") {

        def tmp = Paths.get path

        if (!Files.exists(tmp)) {
            serviceLocator.fileSystem { createDir tmp }
        }

        def ant = new AntBuilder()
        ant.untar(src: file, dest: "${path + File.separator}", overwrite: true, compression: DEFAULT_COMPRESSION)

        def perms = PosixFilePermissions.fromString(FileSystemService.DEFAULT_PERMISSIONS)

        tmp.toFile().eachFileRecurse {

            try {
                Files.setPosixFilePermissions(Paths.get(it.toURI()), perms)
            } catch (e) {
                // TODO handle exception
                //println "catched"
            }
        }

    }

    def publish( pack, env = DEV_ENV, destination = '/tmp/proj/'){

        pack = Paths.get pack
        destination = Paths.get destination
        def name = pack.fileName.toString().split("_api")[0]

        unpack pack.toString(), destination.toString()
        unpack destination.resolve( "${name}.tar.gz" ), destination.toString()

        deploy( destination.resolve("$name").toString(), env )

    }

}
