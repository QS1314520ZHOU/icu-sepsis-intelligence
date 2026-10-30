// 在医院 mongosh 上用管理员登录后执行，为业务库创建最小权限账号。
// 用法（把占位符换成现场真实值，不要把密码提交到 Git）：
//   mongosh "mongodb://<admin-host>:<port>/admin?replicaSet=<RS>" \
//     -u <admin> -p '<admin-pwd>' --eval "load('scripts/create-business-user.js')"
//
// 修改下方 pwd / bizDb / user 后执行；业务 URI 示例：
//   mongodb://<app-user>:<url-encoded-pwd>@host:port/<bizDb>?replicaSet=<RS>&authSource=admin
//
// 注意：只创建业务账号并授权，不修改 SmartCare / DataCenter 源库数据。

(function () {
  const bizDb = "icu_sepsis_dev"; // 改为现场业务库名，须以 icu_sepsis_ 开头
  const user = "sepsis_app";
  const pwd = "CHANGE_ME_MIN_16_CHARS"; // 改为强密码；URI 中 @ → %40

  const dbh = db.getSiblingDB(bizDb);
  const existing = dbh.getUser(user);
  const roles = [
    { role: "readWrite", db: bizDb },
    { role: "dbAdmin", db: bizDb }
  ];
  if (existing) {
    print("[info] user exists; updating roles/pwd...");
    dbh.updateUser(user, { pwd: pwd, roles: roles });
  } else {
    dbh.createUser({ user: user, pwd: pwd, roles: roles });
    print("[ok] created user " + user + " on " + bizDb);
  }
  const u = dbh.getUser(user);
  print("[info] roles = " + JSON.stringify(u ? u.roles : null));
})();
