package net.lshift.diffa.schema.migrations.steps

import net.lshift.diffa.schema.migrations.VerifiedMigrationStep
import org.hibernate.cfg.Configuration
import net.lshift.hibernate.migrations.MigrationBuilder
import java.sql.Types
import Step0048.{createSpace,createUser}
import scala.collection.JavaConversions._
import org.hibernate.mapping.Column

/**
 * Database step adding access control policies.
 */

object Step0050 extends VerifiedMigrationStep {

  def versionId = 50
  def name = "Access control policies"

  def createMigration(config: Configuration) = {
    val migration = new MigrationBuilder(config)

    migration.createTable("privileges").
      column("name", Types.VARCHAR, 50, false).
      pk("name")

    migration.createTable("policies").
      column("space", Types.BIGINT, false).
      column("name", Types.VARCHAR, 50, false).
      pk("space", "name")
    migration.alterTable("policies").
      addForeignKey("fk_plcy_spcs", "space", "spaces", "id")

    migration.createTable("policy_statements").
      column("space", Types.BIGINT, false).
      column("policy", Types.VARCHAR, 50, false).
      column("privilege", Types.VARCHAR, 50, false).
      column("target", Types.VARCHAR, 50, true).
      pk("space", "policy", "privilege")
    migration.alterTable("policy_statements").
      addForeignKey("fk_plcy_stmts_plcy", "space", "spaces", "id").
      addForeignKey("fk_plcy_stmts_priv", "privilege", "privileges", "name")

    definePrivileges(migration, "domain-user")

    // Replacement policy for indicating a domain user
    createPolicy(migration, "0", "User", "domain-user")

    // Full-access admin policy
      // TODO: Add all privileges against this policy
    createPolicy(migration, "0", "Admin")

    // We can no longer create a foreign key based purely upon the user being a member of the space. Instead, just
    // ensure the user exists.
    migration.alterTable("user_item_visibility").
      dropForeignKey("fk_uiv_mmbs").
      addForeignKey("fk_uiv_user", Array("username"), "users", Array("name"))

    // NOTE: A membership also needs to reference the space where the policy was defined.
    //       This allow a policy to be defined at a high level, and then be used in applied
    //       to specific child spaces (ie, define a top level "Admin" policy, but then only apply it to a specific subspace).
    migration.alterTable("members").
      addColumn("policy", Types.VARCHAR, 50, false, "Admin").
      addColumn("policy_space", Types.BIGINT, Column.DEFAULT_LENGTH, false, 0).
      dropPrimaryKey().
      addPrimaryKey("space", "username", "policy_space", "policy").
      addForeignKey("fk_mmbs_plcy", Array("policy_space", "policy"), "policies", Array("space", "name"))

    migration
  }

  def applyVerification(config: Configuration) = {
    val migration = new MigrationBuilder(config)

    val spaceName = randomString()
    val spaceId = randomInt()

    createSpace(migration, spaceId, spaceName)

    val user = randomString()
    val policy = randomString()
    val privilege1 = randomString()
    val privilege2 = randomString()

    createUser(migration, user)
    definePrivileges(migration, privilege1, privilege2)
    createPolicy(migration, spaceId, policy, privilege1, privilege2)
    createMember(migration, spaceId, user, spaceId, policy)

    migration
  }

  def definePrivileges(migration:MigrationBuilder, privileges:String*) {
    privileges.foreach(p =>
      migration.insert("privileges").values(Map(
        "name" -> p
      ))
    )
  }

  def createPolicy(migration:MigrationBuilder, spaceId:String, name:String, privileges:String*) {
    migration.insert("policies").values(Map(
      "space" -> spaceId,
      "name" -> name
    ))

    privileges.foreach(p =>
      migration.insert("policy_statements").values(Map(
        "space" -> spaceId,
        "policy" -> name,
        "privilege" -> p
      ))
    )
  }

  def createMember(migration:MigrationBuilder, spaceId:String, user:String, policySpaceId:String, policy:String) {
    migration.insert("members").values(Map(
      "space" -> spaceId,
      "username" -> user,
      "policy_space" -> policySpaceId,
      "policy" -> policy
    ))
  }
}
