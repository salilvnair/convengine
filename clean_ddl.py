import os
import re

files = ["ddl.sql", "ddl_postgres.sql", "ddl_sqlite.sql", "ddl_oracle.sql"]
base = "/Users/salilvnair/workspace/git/salilvnair/convengine/src/main/resources/sql"

for f in files:
    path = os.path.join(base, f)
    if not os.path.exists(path):
        continue
        
    with open(path, "r") as file:
        content = file.read()
    
    # Extract table names in the order they were created or we can just extract all of them
    # To drop them safely, reversing the creation order is ideal because of foreign keys
    table_matches = re.finditer(r"CREATE TABLE ([a-zA-Z0-9_]+)", content)
    tables = [m.group(1) for m in table_matches]
    tables.reverse() # reverse to handle FK dependencies
    
    # Clean up comments
    # Matches "-- public.xxx definition", "-- Drop table", "-- DROP TABLE xxx;" and blank lines around them
    content = re.sub(r"-- .* definition\n+", "", content)
    content = re.sub(r"-- Drop table\n+", "", content)
    content = re.sub(r"-- DROP TABLE [a-zA-Z0-9_]+;\n+", "", content)
    
    # Also strip multiple empty lines down to a single empty line for cleanliness
    content = re.sub(r"\n{3,}", "\n\n", content)
    content = content.lstrip() # remove any leading whitespace
    
    # Generate drop statements
    drop_stmts = []
    for t in tables:
        if "oracle" in f:
            # Oracle doesn't support IF EXISTS easily
            drop_stmts.append(f"DROP TABLE {t};")
        elif "postgres" in f or f == "ddl.sql":
            drop_stmts.append(f"DROP TABLE IF EXISTS {t} CASCADE;")
        elif "sqlite" in f:
            drop_stmts.append(f"DROP TABLE IF EXISTS {t};")
            
    header = "\n".join(drop_stmts) + "\n\n"
    
    with open(path, "w") as file:
        file.write(header + content)
        
    print(f"Processed {f} with {len(tables)} drop statements.")
