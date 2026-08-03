package com.manzhushaka.agent.controlplane;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** JDBC source of truth for knowledge metadata and leased indexing. */
public final class JdbcKnowledgeRepository implements KnowledgeRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public JdbcKnowledgeRepository(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
    }

    @Override public List<Map<String, Object>> listKnowledgeBases() { return jdbc.query("SELECT * FROM agent_knowledge_base ORDER BY updated_at DESC", (rs, n) -> base(rs)); }
    @Override public Optional<Map<String, Object>> findKnowledgeBase(String id) { return jdbc.query("SELECT * FROM agent_knowledge_base WHERE id = ?", (rs, n) -> base(rs), id).stream().findFirst(); }
    @Override public List<Map<String, Object>> listDocuments(String kb, boolean deleted) { return jdbc.query("SELECT * FROM agent_knowledge_document WHERE knowledge_base_id = ?" + (deleted ? "" : " AND deleted_at IS NULL") + " ORDER BY updated_at DESC", (rs, n) -> document(rs), kb); }
    @Override public Optional<Map<String, Object>> findDocument(String id) { return jdbc.query("SELECT * FROM agent_knowledge_document WHERE id = ?", (rs, n) -> document(rs), id).stream().findFirst(); }
    @Override public Optional<Map<String, Object>> findDocumentVersion(String id) { return jdbc.query("SELECT * FROM agent_knowledge_document_version WHERE id = ?", (rs, n) -> version(rs), id).stream().findFirst(); }
    @Override public List<Map<String, Object>> listDocumentVersions(String documentId) { return jdbc.query("SELECT * FROM agent_knowledge_document_version WHERE document_id=? ORDER BY version_no", (rs,n)->version(rs), documentId); }

    @Override public void saveKnowledgeBase(Map<String, Object> value, ControlPlaneAudit audit) {
        tx.executeWithoutResult(s -> {
            int count = jdbc.update("UPDATE agent_knowledge_base SET code=?,display_name=?,description_text=?,config_json=CAST(? AS JSON),status=?,updated_at=? WHERE id=?", value.get("code"), value.get("displayName"), nullable(value.get("description")), write(map(value.get("config"))), value.get("status"), time(value.get("updatedAt")), value.get("id"));
            if (count == 0) jdbc.update("INSERT INTO agent_knowledge_base(id,code,display_name,description_text,config_json,status,created_at,updated_at) VALUES(?,?,?,?,CAST(? AS JSON),?,?,?)", value.get("id"), value.get("code"), value.get("displayName"), nullable(value.get("description")), write(map(value.get("config"))), value.get("status"), time(value.get("createdAt")), time(value.get("updatedAt")));
            audit(audit);
        });
    }

    @Override public void createDocumentVersionAndJob(Map<String, Object> doc, Map<String, Object> ver, Map<String, Object> job, String objectCleanupId, ControlPlaneAudit audit) {
        tx.executeWithoutResult(s -> { insertDocument(doc); insertVersion(ver); insertJob(job); disarmObjectCleanup(objectCleanupId); audit(audit); });
    }

    @Override public void createNextVersionAndJob(String documentId, Map<String, Object> ver, Map<String, Object> job, String objectCleanupId, Instant now, ControlPlaneAudit audit) {
        tx.executeWithoutResult(s -> {
            if (jdbc.update("UPDATE agent_knowledge_document SET current_version_id=?,status='QUEUED',updated_at=? WHERE id=? AND deleted_at IS NULL", ver.get("id"), Timestamp.from(now), documentId) == 0) throw new IllegalArgumentException("文档不存在。");
            insertVersion(ver); insertJob(job); disarmObjectCleanup(objectCleanupId); audit(audit);
        });
    }

    @Override public List<Map<String, Object>> claimIndexJobs(String owner, Instant now, Instant until, int limit) {
        return tx.execute(s -> {
            jdbc.update("UPDATE agent_knowledge_index_job j JOIN agent_knowledge_document d ON d.id=j.document_id JOIN agent_knowledge_base b ON b.id=j.knowledge_base_id "
                            + "SET j.status='CANCELLED',j.active_job_key=NULL,j.lease_owner=NULL,j.lease_token=NULL,j.lease_until=NULL,j.finished_at=?,j.updated_at=? "
                            + "WHERE j.status IN ('QUEUED','RUNNING') AND (b.status<>'ACTIVE' OR d.deleted_at IS NOT NULL OR d.current_version_id<>j.document_version_id)",
                    Timestamp.from(now), Timestamp.from(now));
            List<Map<String, Object>> candidates = jdbc.query(
                    "SELECT j.* FROM agent_knowledge_index_job j JOIN agent_knowledge_document d ON d.id=j.document_id JOIN agent_knowledge_base b ON b.id=j.knowledge_base_id "
                            + "WHERE b.status='ACTIVE' AND d.deleted_at IS NULL AND d.current_version_id=j.document_version_id "
                            + "AND ((j.status='QUEUED' AND j.next_attempt_at<=?) OR (j.status='RUNNING' AND j.lease_until<=?)) "
                            + "ORDER BY j.created_at LIMIT ? FOR UPDATE SKIP LOCKED",
                    (rs, n) -> job(rs), Timestamp.from(now), Timestamp.from(now), limit);
            return candidates.stream().flatMap(candidate -> {
                String leaseOwner = uniqueLeaseOwner(owner);
                String leaseToken = UUID.randomUUID().toString();
                int changed = jdbc.update(
                        "UPDATE agent_knowledge_index_job SET status='RUNNING',lease_owner=?,lease_token=?,lease_epoch=lease_epoch+1,lease_until=?,updated_at=? "
                                + "WHERE id=? AND ((status='QUEUED' AND next_attempt_at<=?) OR (status='RUNNING' AND lease_until<=?))",
                        leaseOwner, leaseToken, Timestamp.from(until), Timestamp.from(now), candidate.get("id"), Timestamp.from(now), Timestamp.from(now));
                return changed == 1 ? java.util.stream.Stream.of(withLease(candidate, leaseOwner, leaseToken, until, now)) : java.util.stream.Stream.empty();
            }).toList();
        });
    }

    @Override public boolean completeIndexJob(String id, String owner, String token, List<Map<String, Object>> chunks, Instant now, ControlPlaneAudit audit) {
        return completeIndexJobWithVectorMutation(id, owner, token, chunks, () -> { }, now, audit);
    }

    @Override public boolean completeIndexJobWithVectorMutation(String id, String owner, String token, List<Map<String, Object>> chunks, Runnable vectorMutation, Instant now, ControlPlaneAudit audit) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            Map<String, Object> job = ownedCurrent(id, owner, token, now); if (job == null) return false;
            vectorMutation.run();
            if (ownedCurrent(id, owner, token, Instant.now()) == null) {
                s.setRollbackOnly();
                return false;
            }
            jdbc.update("DELETE FROM agent_knowledge_chunk WHERE document_version_id=?", job.get("documentVersionId"));
            for (Map<String, Object> c : chunks) jdbc.update("INSERT INTO agent_knowledge_chunk(id,knowledge_base_id,document_id,document_version_id,chunk_index,content_text,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)", c.get("id"), c.get("knowledgeBaseId"), c.get("documentId"), c.get("documentVersionId"), number(c.get("chunkIndex")), c.get("content"), Boolean.TRUE.equals(c.get("enabled")), time(c.get("createdAt")), time(c.get("updatedAt")));
            jdbc.update("UPDATE agent_knowledge_document_version SET status='INDEXED',indexed_at=?,updated_at=? WHERE id=?", Timestamp.from(now), Timestamp.from(now), job.get("documentVersionId"));
            jdbc.update("UPDATE agent_knowledge_document SET status='INDEXED',updated_at=? WHERE id=? AND deleted_at IS NULL", Timestamp.from(now), job.get("documentId"));
            jdbc.update("UPDATE agent_knowledge_index_job SET active_job_key=NULL,status='SUCCEEDED',lease_owner=NULL,lease_token=NULL,lease_until=NULL,finished_at=?,updated_at=? WHERE id=?", Timestamp.from(now), Timestamp.from(now), id);
            audit(audit); return true;
        }));
    }

    @Override public boolean failIndexJob(String id, String owner, String token, String error, Instant retryAt, Instant now, ControlPlaneAudit audit) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            int count = jdbc.update("UPDATE agent_knowledge_index_job SET active_job_key=NULL,status='FAILED',attempts=attempts+1,last_error_code=?,next_attempt_at=?,lease_owner=NULL,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_token=? AND lease_until>?", error, Timestamp.from(retryAt), Timestamp.from(now), id, owner, token, Timestamp.from(now));
            if (count == 0) return false; audit(audit); return true;
        }));
    }

    @Override public Optional<Map<String, Object>> retryIndexJob(String id, Instant now, ControlPlaneAudit audit) {
        return tx.execute(s -> {
            Map<String, Object> before = jdbc.query("SELECT * FROM agent_knowledge_index_job WHERE id=? FOR UPDATE", (rs, n) -> job(rs), id).stream().findFirst().orElse(null);
            if (before == null || !"FAILED".equals(before.get("status"))) return Optional.empty();
            try { jdbc.update("UPDATE agent_knowledge_index_job SET active_job_key=document_version_id,status='QUEUED',next_attempt_at=?,lease_owner=NULL,lease_token=NULL,lease_until=NULL,last_error_code=NULL,updated_at=? WHERE id=?", Timestamp.from(now), Timestamp.from(now), id); }
            catch (DuplicateKeyException ex) { return Optional.empty(); }
            audit(audit); return Optional.of(withStatus(before, "QUEUED", now));
        });
    }

    @Override public List<Map<String, Object>> listIndexJobs(String kb) { return kb == null || kb.isBlank() ? jdbc.query("SELECT * FROM agent_knowledge_index_job ORDER BY created_at DESC", (rs, n) -> job(rs)) : jdbc.query("SELECT * FROM agent_knowledge_index_job WHERE knowledge_base_id=? ORDER BY created_at DESC", (rs, n) -> job(rs), kb); }
    @Override public List<Map<String, Object>> listChunks(String kb, String doc, String ver) {
        String sql = "SELECT c.* FROM agent_knowledge_chunk c JOIN agent_knowledge_document d ON d.id=c.document_id WHERE c.knowledge_base_id=? AND d.deleted_at IS NULL AND d.current_version_id=c.document_version_id" + (doc == null ? "" : " AND c.document_id=?") + (ver == null ? "" : " AND c.document_version_id=?") + " ORDER BY c.chunk_index";
        return doc == null && ver == null ? jdbc.query(sql, (rs,n) -> chunk(rs), kb) : doc != null && ver == null ? jdbc.query(sql, (rs,n) -> chunk(rs), kb, doc) : doc == null ? jdbc.query(sql, (rs,n) -> chunk(rs), kb, ver) : jdbc.query(sql, (rs,n) -> chunk(rs), kb, doc, ver);
    }
    @Override public Optional<Map<String, Object>> findChunk(String id) { return jdbc.query("SELECT * FROM agent_knowledge_chunk WHERE id=?", (rs,n)->chunk(rs), id).stream().findFirst(); }
    @Override public boolean setChunkEnabled(String id, boolean enabled, Instant now, ControlPlaneAudit audit) { return Boolean.TRUE.equals(tx.execute(s -> { int n=jdbc.update("UPDATE agent_knowledge_chunk SET enabled=?,updated_at=? WHERE id=?", enabled, Timestamp.from(now), id); if(n==0)return false; audit(audit);return true;})); }
    @Override public boolean setChunkEnabledAndEnqueueVectorSync(String id, boolean enabled, Map<String,Object> cleanup, Instant now, ControlPlaneAudit audit) { return Boolean.TRUE.equals(tx.execute(s -> { int n=jdbc.update("UPDATE agent_knowledge_chunk SET enabled=?,updated_at=? WHERE id=?", enabled, Timestamp.from(now), id); if(n==0)return false; jdbc.update("INSERT INTO agent_knowledge_object_cleanup(id,object_key,knowledge_base_id,document_version_id,reason_code,status,attempts,created_at,updated_at) VALUES(?,?,?,?,?,'PENDING',0,?,?)", cleanup.get("id"), cleanup.get("objectKey"), cleanup.get("knowledgeBaseId"), nullable(cleanup.get("documentVersionId")), cleanup.get("reasonCode"), time(cleanup.get("createdAt")), time(cleanup.get("updatedAt"))); audit(audit);return true;})); }
    @Override public Optional<Map<String, Object>> markDocumentDeleted(String id, Instant now, ControlPlaneAudit audit) { return tx.execute(s -> { if(jdbc.update("UPDATE agent_knowledge_document SET status='DELETED',deleted_at=?,updated_at=? WHERE id=? AND deleted_at IS NULL",Timestamp.from(now),Timestamp.from(now),id)==0)return Optional.empty(); jdbc.update("DELETE FROM agent_knowledge_chunk WHERE document_id=?", id); jdbc.update("UPDATE agent_knowledge_index_job SET status='CANCELLED',active_job_key=NULL,lease_owner=NULL,lease_token=NULL,lease_until=NULL,finished_at=?,updated_at=? WHERE document_id=? AND status IN ('QUEUED','RUNNING')", Timestamp.from(now), Timestamp.from(now), id); audit(audit);return findDocument(id); }); }
    @Override public List<Map<String, Object>> listDeletedDocumentVersionsAwaitingCompensation(int limit) { return jdbc.query("SELECT v.* FROM agent_knowledge_document_version v JOIN agent_knowledge_document d ON d.id=v.document_id WHERE d.deleted_at IS NOT NULL AND v.object_deleted_at IS NULL ORDER BY d.deleted_at LIMIT ?",(rs,n)->version(rs),limit); }
    @Override public boolean markVersionObjectDeleted(String id, Instant now, ControlPlaneAudit audit) { return Boolean.TRUE.equals(tx.execute(s->{int n=jdbc.update("UPDATE agent_knowledge_document_version SET object_deleted_at=?,updated_at=? WHERE id=? AND object_deleted_at IS NULL",Timestamp.from(now),Timestamp.from(now),id);if(n==0)return false;audit(audit);return true;})); }

    @Override public void enqueueObjectCleanup(Map<String, Object> cleanup, ControlPlaneAudit audit) { tx.executeWithoutResult(s -> { try { jdbc.update("INSERT INTO agent_knowledge_object_cleanup(id,object_key,knowledge_base_id,document_version_id,reason_code,status,attempts,created_at,updated_at) VALUES(?,?,?,?,?,?,0,?,?)", cleanup.get("id"),cleanup.get("objectKey"),cleanup.get("knowledgeBaseId"),nullable(cleanup.get("documentVersionId")),cleanup.get("reasonCode"),cleanup.getOrDefault("status", "PENDING"),time(cleanup.get("createdAt")),time(cleanup.get("updatedAt"))); audit(audit); } catch (DuplicateKeyException ignored) { } }); }
    @Override public void activateObjectCleanup(String id, Instant now) { jdbc.update("UPDATE agent_knowledge_object_cleanup SET status='PENDING',updated_at=? WHERE id=? AND status<>'SUCCEEDED'", Timestamp.from(now), id); }
    @Override public List<Map<String, Object>> listObjectCleanupCandidates(int limit) { return jdbc.query("SELECT * FROM agent_knowledge_object_cleanup WHERE status='PENDING' OR (status='PREPARED' AND created_at<=?) ORDER BY created_at LIMIT ?",(rs,n)->cleanup(rs), Timestamp.from(Instant.now().minus(java.time.Duration.ofMinutes(5))), limit); }
    @Override public boolean completeObjectCleanup(String id, Instant now, ControlPlaneAudit audit) { return Boolean.TRUE.equals(tx.execute(s->{int n=jdbc.update("UPDATE agent_knowledge_object_cleanup SET status='SUCCEEDED',completed_at=?,updated_at=? WHERE id=? AND status IN ('PENDING','PREPARED')",Timestamp.from(now),Timestamp.from(now),id);if(n==0)return false;audit(audit);return true;})); }
    @Override public void recordObjectCleanupFailure(String id, String error, Instant now) { jdbc.update("UPDATE agent_knowledge_object_cleanup SET attempts=attempts+1,last_error_code=?,updated_at=? WHERE id=? AND status='PENDING'",error,Timestamp.from(now),id); }

    private void insertDocument(Map<String,Object> d) { jdbc.update("INSERT INTO agent_knowledge_document(id,knowledge_base_id,file_name,content_type,current_version_id,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)", d.get("id"),d.get("knowledgeBaseId"),d.get("name"),d.get("contentType"),d.get("currentVersionId"),d.get("status"),time(d.get("createdAt")),time(d.get("updatedAt"))); }
    private void insertVersion(Map<String,Object> v) { jdbc.update("INSERT INTO agent_knowledge_document_version(id,document_id,knowledge_base_id,version_no,object_key,content_type,size_bytes,sha256,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",v.get("id"),v.get("documentId"),v.get("knowledgeBaseId"),number(v.get("version")),v.get("objectKey"),v.get("contentType"),number(v.get("size")),v.get("sha256"),v.get("status"),time(v.get("createdAt")),time(v.get("updatedAt"))); }
    private void insertJob(Map<String,Object> j) { jdbc.update("INSERT INTO agent_knowledge_index_job(id,knowledge_base_id,document_id,document_version_id,active_job_key,status,attempts,next_attempt_at,created_at,updated_at) VALUES(?,?,?,?,?,'QUEUED',0,?,?,?)",j.get("id"),j.get("knowledgeBaseId"),j.get("documentId"),j.get("documentVersionId"),j.get("documentVersionId"),time(j.get("nextAttemptAt")),time(j.get("createdAt")),time(j.get("updatedAt"))); }
    private void disarmObjectCleanup(String id) { if (id == null) return; if (jdbc.update("UPDATE agent_knowledge_object_cleanup SET status='SUCCEEDED',completed_at=?,updated_at=? WHERE id=? AND status='PREPARED'", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), id) != 1) throw new IllegalStateException("OBJECT_CLEANUP_INTENT_MISSING"); }
    private Map<String,Object> ownedCurrent(String id,String owner,String token,Instant now){return jdbc.query("SELECT j.* FROM agent_knowledge_index_job j JOIN agent_knowledge_document d ON d.id=j.document_id JOIN agent_knowledge_base b ON b.id=j.knowledge_base_id WHERE j.id=? AND j.status='RUNNING' AND j.lease_owner=? AND j.lease_token=? AND j.lease_until>? AND b.status='ACTIVE' AND d.deleted_at IS NULL AND d.current_version_id=j.document_version_id FOR UPDATE",(rs,n)->job(rs),id,owner,token,Timestamp.from(now)).stream().findFirst().orElse(null);}
    private Map<String,Object> base(ResultSet r)throws java.sql.SQLException{return out("id",r.getString("id"),"code",r.getString("code"),"displayName",r.getString("display_name"),"description",r.getString("description_text"),"config",read(r.getString("config_json")),"status",r.getString("status"),"createdAt",instant(r.getTimestamp("created_at")),"updatedAt",instant(r.getTimestamp("updated_at")));}
    private Map<String,Object> document(ResultSet r)throws java.sql.SQLException{return out("id",r.getString("id"),"knowledgeBaseId",r.getString("knowledge_base_id"),"name",r.getString("file_name"),"contentType",r.getString("content_type"),"currentVersionId",r.getString("current_version_id"),"status",r.getString("status"),"deletedAt",instant(r.getTimestamp("deleted_at")),"createdAt",instant(r.getTimestamp("created_at")),"updatedAt",instant(r.getTimestamp("updated_at")));}
    private Map<String,Object> version(ResultSet r)throws java.sql.SQLException{return out("id",r.getString("id"),"documentId",r.getString("document_id"),"knowledgeBaseId",r.getString("knowledge_base_id"),"version",r.getInt("version_no"),"objectKey",r.getString("object_key"),"contentType",r.getString("content_type"),"size",r.getLong("size_bytes"),"sha256",r.getString("sha256"),"status",r.getString("status"),"indexedAt",instant(r.getTimestamp("indexed_at")),"objectDeletedAt",instant(r.getTimestamp("object_deleted_at")),"createdAt",instant(r.getTimestamp("created_at")),"updatedAt",instant(r.getTimestamp("updated_at")));}
    private Map<String,Object> chunk(ResultSet r)throws java.sql.SQLException{return out("id",r.getString("id"),"knowledgeBaseId",r.getString("knowledge_base_id"),"documentId",r.getString("document_id"),"documentVersionId",r.getString("document_version_id"),"chunkIndex",r.getInt("chunk_index"),"content",r.getString("content_text"),"enabled",r.getBoolean("enabled"),"createdAt",instant(r.getTimestamp("created_at")),"updatedAt",instant(r.getTimestamp("updated_at")));}
    private Map<String,Object> job(ResultSet r)throws java.sql.SQLException{return out("id",r.getString("id"),"knowledgeBaseId",r.getString("knowledge_base_id"),"documentId",r.getString("document_id"),"documentVersionId",r.getString("document_version_id"),"status",r.getString("status"),"attempts",r.getInt("attempts"),"nextAttemptAt",instant(r.getTimestamp("next_attempt_at")),"leaseOwner",r.getString("lease_owner"),"leaseToken",r.getString("lease_token"),"leaseEpoch",r.getLong("lease_epoch"),"leaseUntil",instant(r.getTimestamp("lease_until")),"lastErrorCode",r.getString("last_error_code"),"finishedAt",instant(r.getTimestamp("finished_at")),"createdAt",instant(r.getTimestamp("created_at")),"updatedAt",instant(r.getTimestamp("updated_at")));}
    private Map<String,Object> cleanup(ResultSet r)throws java.sql.SQLException{return out("id",r.getString("id"),"objectKey",r.getString("object_key"),"knowledgeBaseId",r.getString("knowledge_base_id"),"documentVersionId",r.getString("document_version_id"),"reasonCode",r.getString("reason_code"),"status",r.getString("status"),"attempts",r.getInt("attempts"),"lastErrorCode",r.getString("last_error_code"),"createdAt",instant(r.getTimestamp("created_at")),"updatedAt",instant(r.getTimestamp("updated_at")),"completedAt",instant(r.getTimestamp("completed_at")));}
    private Map<String,Object> withLease(Map<String,Object> v,String owner,String token,Instant until,Instant now){Map<String,Object>x=new LinkedHashMap<>(v);x.put("status","RUNNING");x.put("leaseOwner",owner);x.put("leaseToken",token);x.put("leaseEpoch",((Number)v.getOrDefault("leaseEpoch",0L)).longValue()+1);x.put("leaseUntil",until.toString());x.put("updatedAt",now.toString());return Map.copyOf(x);} private Map<String,Object> withStatus(Map<String,Object>v,String status,Instant now){Map<String,Object>x=new LinkedHashMap<>(v);x.put("status",status);x.remove("leaseOwner");x.remove("leaseToken");x.remove("leaseUntil");x.remove("lastErrorCode");x.put("updatedAt",now.toString());return Map.copyOf(x);}
    private String uniqueLeaseOwner(String workerId){String prefix=workerId==null||workerId.isBlank()?"knowledge-worker":workerId;return (prefix.length()>91?prefix.substring(0,91):prefix)+"-"+UUID.randomUUID();}
    private void audit(ControlPlaneAudit a){jdbc.update("INSERT INTO agent_control_plane_audit(id,actor_username,action_code,resource_type,resource_id,metadata_json,created_at) VALUES(?,?,?,?,?,CAST(? AS JSON),?)",a.id(),a.actor(),a.action(),a.resourceType(),a.resourceId(),write(a.metadata()),Timestamp.from(a.createdAt()));}
    private Map<String,Object> out(Object...v){Map<String,Object>x=new LinkedHashMap<>();for(int i=0;i<v.length;i+=2)if(v[i+1]!=null)x.put(String.valueOf(v[i]),v[i+1]);return Map.copyOf(x);} @SuppressWarnings("unchecked") private Map<String,Object> map(Object v){return v instanceof Map<?,?> raw?(Map<String,Object>)raw:Map.of();} private String write(Map<String,Object>v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException("知识库 JSON 写入失败",e);}} private Map<String,Object> read(String v){if(v==null||v.isBlank())return Map.of();try{return json.readValue(v,MAP_TYPE);}catch(Exception e){throw new IllegalStateException("知识库 JSON 读取失败",e);}} private Timestamp time(Object v){return v==null?null:Timestamp.from(Instant.parse(String.valueOf(v)));} private String instant(Timestamp v){return v==null?null:v.toInstant().toString();} private Object nullable(Object v){return v==null||String.valueOf(v).isBlank()?null:v;} private long number(Object v){return v instanceof Number n?n.longValue():Long.parseLong(String.valueOf(v));}
}
