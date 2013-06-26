import java.security.*
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec
import java.security.spec.RSAPrivateKeySpec;
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import sun.security.x509.*;
import java.security.cert.*;

import java.nio.file.Paths 
import java.nio.file.Path 
import java.nio.file.Files

import static Frapid.*

class DigitalSignatureService {

    protected static final String PUBLIC_KEY_FILE_NAME = "public.key"
    protected static final String PRIVATE_KEY_FILE_NAME = "private.key"
    protected static final String SHA1WITH_RSA = "SHA1withRSA"

    def config, serviceLocator, deployer

    def DigitalSignatureService() {

       def frapidPath = System.getenv()["FRAPID_HOME"]
       config = new ConfigSlurper().parse( new File( "${frapidPath}/config.groovy" ).toURL() )
  
       serviceLocator = ServiceLocator.instance

    }

    def verifyPack( pack, publicKey = null, signature = null ) {

        pack = Paths.get pack
        if( !signature ) {
            def name = pack.fileName.toString().split('_api')[0]
            
            def tmpPack = Paths.get config.frapid.tmp, "${name}.tar.gz"
            def tmpSignature = Paths.get config.frapid.tmp, "${name}.sig"
            
            Files.deleteIfExists tmpPack
            Files.deleteIfExists tmpSignature
            
            deployer = serviceLocator.get DEPLOYER
            deployer.unpack pack, config.frapid.tmp
            
            pack = tmpPack
            signature = tmpSignature
            
        }
        
        signature = new File( signature.toString() ).bytes

        def pubKeyPath = Paths.get( config.frapid.keyDir , PUBLIC_KEY_FILE_NAME)
        if( publicKey ) pubKeyPath = Paths.get( publicKey )
      
        publicKey = getPublicKey pubKeyPath.toFile()
        def signatureManager = Signature.getInstance(SHA1WITH_RSA);
        signatureManager.initVerify publicKey

        FileInputStream fis = new FileInputStream( pack.toFile() );
        BufferedInputStream bufin = new BufferedInputStream(fis);
        byte[] buffer = new byte[1024];
        int len;
        while ((len = bufin.read(buffer)) >= 0) {
            signatureManager.update(buffer, 0, len)
        };
        bufin.close();

        signatureManager.verify signature
        
    }

    def generateKeys() {

        def keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(1024 );
      
        def keyPair = keyGen.generateKeyPair();
        def privateKey = keyPair.getPrivate();
        def publicKey = keyPair.getPublic();

        def keyDir = Paths.get config.frapid.keyDir 
        if( !Files.exists(keyDir) ) {
            serviceLocator.fileSystem { createDir keyDir }
        }   
        
        def pubKeyPath = Paths.get config.frapid.keyDir , PUBLIC_KEY_FILE_NAME
        def privKeyPath = Paths.get config.frapid.keyDir , PRIVATE_KEY_FILE_NAME
        
        Files.write( pubKeyPath, publicKey.encoded  )
        Files.write( privKeyPath, privateKey.encoded  )

    }

    def getPrivateKey(File privateKeyFile) throws IOException, GeneralSecurityException {
        byte[] keyBytes = new byte[(int)privateKeyFile.length()];
        FileInputStream fis = new FileInputStream(privateKeyFile);
        fis.read(keyBytes);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        def privKey = (RSAPrivateKey) keyFactory.generatePrivate(spec);
        return privKey;
    }

    def getPublicKey(File privateKeyFile) throws IOException, GeneralSecurityException {
        byte[] keyBytes = new byte[(int)privateKeyFile.length()];
        FileInputStream fis = new FileInputStream(privateKeyFile);
        fis.read(keyBytes);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        def pubKey = (RSAPublicKey) keyFactory.generatePublic(spec);
        return pubKey;
    }
    
    def createPrivateKey( privateKeyPath ){

        def keyEncoded = Files.readAllBytes privateKeyPath

        def keyFactory = KeyFactory.getInstance( "RSA" );
        def keySpec = new RSAPrivateKeySpec(keyEncoded);
        return keyFactory.generatePrivate(keySpec);
                   
    }

    def generateCertificate( dn, privKey, pubKey, days, algorithm) {
        X509CertInfo info = new X509CertInfo();
        Date from = new Date();
        Date to = new Date(from.getTime() + days * 86400000l);
        CertificateValidity interval = new CertificateValidity(from, to);
        BigInteger sn = new BigInteger(64, new SecureRandom());
        X500Name owner = new X500Name(dn);
 
        info.set(X509CertInfo.VALIDITY, interval);
        info.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(sn));
        info.set(X509CertInfo.SUBJECT, new CertificateSubjectName(owner));
        info.set(X509CertInfo.ISSUER, new CertificateIssuerName(owner));
        info.set(X509CertInfo.KEY, new CertificateX509Key(pubKey) );
        info.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3));
        AlgorithmId algo = new AlgorithmId(AlgorithmId.md5WithRSAEncryption_oid);
        info.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algo));
 
        // Sign the cert to identify the algorithm that's used.
        X509CertImpl cert = new X509CertImpl(info);
        cert.sign(privKey, algorithm);
 
        // Update the algorith, and resign.
        algo = (AlgorithmId)cert.get(X509CertImpl.SIG_ALG);
        info.set(CertificateAlgorithmId.NAME + "." + CertificateAlgorithmId.ALGORITHM, algo);
        cert = new X509CertImpl(info);
        cert.sign(privKey, algorithm);
        return cert;
    }

}
