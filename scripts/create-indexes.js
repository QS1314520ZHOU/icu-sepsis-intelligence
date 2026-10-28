// Optional isolated developer inspection/init helper. Application also ensures all indexes.
// Example: mongosh 'mongodb://127.0.0.1:27027/icu_sepsis_demo?replicaSet=rs0' scripts/create-indexes.js
if (!/^icu_sepsis_.*_(demo|test)$/.test(db.getName())) throw Error("Only dedicated development databases allowed");
db.sepsis_clinical_observation.createIndex({patientId:1,encounterId:1,eventTime:-1});
db.sepsis_clinical_observation.createIndex({sourceSystem:1,sourceRecordId:1},{unique:true});
db.sepsis_alert_event.createIndex({department:1,status:1,createdAt:-1});
db.sepsis_alert_event.createIndex({activeKey:1},{unique:true,partialFilterExpression:{activeKey:{$type:"string"}}});
db.sepsis_bundle_task.createIndex({alertId:1,itemCode:1},{unique:true});
db.sepsis_bundle_task.createIndex({department:1,dueAt:1});
db.sepsis_bundle_task.createIndex({department:1,triggerTime:1});
db.sepsis_bundle_execution.createIndex({taskId:1,sourceSystem:1,sourceRecordId:1},{unique:true});
db.sepsis_state_history.createIndex({entityId:1,createdAt:-1});
