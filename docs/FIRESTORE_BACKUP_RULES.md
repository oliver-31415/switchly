# Firestore rules for cloud backups
Switchly stores backup metadata in:
```text
switchly_users/{uid}/backups/{backupId}
```

Full backups additionally store the compressed statistics archive in:
```text
switchly_users/{uid}/backups/{backupId}/stats_chunks/{chunkId}
```

Merge the nested matches below into the existing Firestore rules. Do not replace unrelated Premium, redeem-code or server-side rules.
```firestore
match /switchly_users/{uid}/backups/{backupId} {
  allow read, create, update, delete: if request.auth != null
    && request.auth.uid == uid;

  match /stats_chunks/{chunkId} {
    allow read, create, update, delete: if request.auth != null
      && request.auth.uid == uid;
  }
}
```

The backup list orders only by `created_at`, and statistics chunks order only by `index`. Firestore's automatic single-field indexes cover both queries; no composite index is required.

After each successful upload, Switchly keeps the newest 10 versioned backups and deletes older backup documents together with their `stats_chunks` subcollection documents.
