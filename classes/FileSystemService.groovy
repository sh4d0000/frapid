import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import static java.nio.file.StandardCopyOption.*

class FileSystemService {

    protected static final String DEFAULT_PERMISSIONS = "rwxrwxrwx"

    def copy(from, to, String... fileNames = null ) {

        def perms = PosixFilePermissions.fromString DEFAULT_PERMISSIONS
        def fileCopied = null

        def fromPath = Path.class.isInstance(from)? from : Paths.get(from.toString())
        def toPath   = Path.class.isInstance(to)  ? to   : Paths.get(to.toString())

        if( fileNames == null ) {

            fileCopied = Files.copy fromPath, toPath, REPLACE_EXISTING, COPY_ATTRIBUTES
            Files.setPosixFilePermissions fileCopied, perms

            return fileCopied

        } else {

            def filesCopied = fileNames.collect { fileName ->

                fileCopied = Files.copy fromPath.resolve( fileName ), toPath.resolve( fileName ), REPLACE_EXISTING, COPY_ATTRIBUTES
                Files.setPosixFilePermissions fileCopied, perms

                fileCopied
            }

            return filesCopied.size() == 1? filesCopied[0] : filesCopied
        }

    }

    def createDir( dir, defaultPermission = true ) {

        def dirPath = Path.class.isInstance(dir)? dir : Paths.get( dir.toString() )
        Files.createDirectory dirPath

        if( defaultPermission ) {
            def perms = PosixFilePermissions.fromString DEFAULT_PERMISSIONS
            Files.setPosixFilePermissions dirPath, perms
        }

        return dirPath
    }


}
