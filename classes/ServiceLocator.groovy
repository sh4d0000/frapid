class ServiceLocator {

    private services, instances

    def ServiceLocator() {

        instances = [:]
        services = [:]

        services.fileSystem = FileSystemService
        services.scaffolder = ScaffolderService
        services.digitalSignature = DigitalSignatureService
        services.projectManager = ProjectManager

    }

    def fileSystem(Closure c){

        c.delegate = services.fileSystem.newInstance()
        c()

    }

    def get( service_name  ) {

	if( !instances.containsKey(service_name)  ) {
	    instances[service_name] = services[service_name].newInstance()
        }

        instances[service_name]

    }




}
