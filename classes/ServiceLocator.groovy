class ServiceLocator {

    private services

    def ServiceLocator() {

        services = [:]
        services.fileSystem = FileSystemService
        services.scaffolder = ScaffolderService
        services.digitalSignature = DigitalSignatureService

    }

    def fileSystem(Closure c){

        c.delegate = services.fileSystem.newInstance()
        c()

    }

    def get( service_name  ) {

       return services[service_name].newInstance()

    }




}
