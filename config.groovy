frapid { 
      
   home = System.getenv()["FRAPID_HOME"] 
   keyDir = System.getenv()["HOME"] + File.separator + ".frapid" 
   temp = home + File.separator + "tmp"
   templates  = home + File.separator + "templates"
   classes = home + File.separator + "classes"
   frapiConfigFile = "frapi_conf.xml"
   mysqlDriver = classes + File.separator + "mysql-connector-java-5.1.22-bin.jar"

}

envs {
   dev {
      type = 'local'
      frapi {

         home = System.getenv()["FRAPI_PATH"]
         custom = home + File.separator + "src/frapi/custom"
         model = custom + File.separator + "Model"
         config = custom + File.separator + "Config"
         action = custom + File.separator + "Action"
         frapid = custom + File.separator + "frapid"
         main_controller = home + File.separator + "src/frapi/library/Frapi/Controller"

      }
   }

   test {
      type = 'remote'
      uri = 'localhost:4444'
   }

   prod {
      type = 'remote'
      uri = 'localhost:4444'
   }
}

drupal {
    home = System.getenv()["DRUPAL_HOME"]
    privateFileSystem = home.toString() + File.separator + "sites/default/private"
    publicFileSystem = home.toString() + File.separator + "sites/default/files"
}
