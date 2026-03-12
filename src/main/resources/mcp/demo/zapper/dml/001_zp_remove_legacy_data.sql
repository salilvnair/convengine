-- Demo data cleanup for current electricity disconnect schema only.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'zp_disco_trans_data') THEN
    EXECUTE 'DELETE FROM zp_disco_trans_data';
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'zp_inventory_data') THEN
    EXECUTE 'DELETE FROM zp_inventory_data';
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'zp_action_status') THEN
    EXECUTE 'DELETE FROM zp_action_status';
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'zp_disco_request') THEN
    EXECUTE 'DELETE FROM zp_disco_request';
  END IF;
END $$;
