class ServiceLocator {

    private services

    def ServiceLocator() {

        services = [:]
        services.fileSystem = new FileSystemService()
        services.digitalSignature = new FileSystemService()

    }

    def fileSystem(Closure c){

        c.delegate = services.fileSystem
        c()

    }




}
