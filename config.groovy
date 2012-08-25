frapid { 
      
   home = System.getenv()["FRAPID_HOME"] 
   temp = home + File.separator + "tmp"
   templates  = home + File.separator + "templates"
   classes = home + File.separator + "classes"

}
  
frapi {

   home = System.getenv()["FRAPI_PATH"]
   custom = home + File.separator + "src/frapi/custom"
   model = custom + File.separator + "Model"
   config = custom + File.separator + "Config"
   action = custom + File.separator + "Action"
   frapid = custom + File.separator + "frapid"

}

