import os

files = ["ddl.sql", "ddl_postgres.sql", "ddl_sqlite.sql", "ddl_oracle.sql"]
base = "/Users/salilvnair/workspace/git/salilvnair/convengine/src/main/resources/sql"

for f in files:
    path = os.path.join(base, f)
    if not os.path.exists(path):
        continue
        
    with open(path, "r") as file:
        content = file.read()
    
    # Split on the first occurrence of INSERT INTO ce_config
    # We add a newline to keep it clean.
    parts = content.split("INSERT INTO ce_config", 1)
    if len(parts) == 2:
        ddl_part = parts[0].strip() + "\n"
        dml_part = "INSERT INTO ce_config" + parts[1].strip() + "\n"
        
        # Write back the pure DDL
        with open(path, "w") as ddl_file:
            ddl_file.write(ddl_part)
            
        # Write the seed file
        seed_name = f.replace("ddl", "seed")
        seed_path = os.path.join(base, seed_name)
        with open(seed_path, "w") as seed_file:
            seed_file.write(dml_part)
            
        print(f"Split {f} -> {f} and {seed_name}")
    else:
        print(f"No DML found in {f}")
