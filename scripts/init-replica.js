// Run only against a new localhost development MongoDB instance.
try { rs.status(); print("Replica set already initialized"); }
catch (e) {
  if (e.codeName !== "NotYetInitialized") throw e;
  rs.initiate({_id:"rs0",members:[{_id:0,host:"127.0.0.1:27027"}]});
}
