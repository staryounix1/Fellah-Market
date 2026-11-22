-- Fellah-Market MVP — Souss-Massa Realistic Seed Data

insert into public.regions (id, name_darija, name_ar, name_fr, cities, is_default) values
  ('reg_souss_massa', 'سوس ماسة', 'سوس ماسة', 'Souss-Massa', array['أكادير','تارودانت','اشتوكة أيت باها','إنزكان','أولاد تايمة','تيزنيت'], true),
  ('reg_marrakech', 'مراكش آسفي', 'مراكش آسفي', 'Marrakech-Safi', array['مراكش','قلعة السراغنة','شيشاوة','الصويرة'], false),
  ('reg_casablanca', 'الدار البيضاء سطات', 'الدار البيضاء سطات', 'Casablanca-Settat', array['الدار البيضاء','برشيد','سطات','الجديدة'], false)
on conflict (id) do nothing;

insert into public.categories (id, name_darija, name_ar, name_fr, icon_key, sort_order) values
  ('cat_veg', 'الخضراوات', 'الخضروات', 'Légumes', 'veg', 1),
  ('cat_citrus', 'الحوامض', 'الحوامض', 'Agrumes', 'citrus', 2),
  ('cat_olives', 'الزيتون وزيت العود', 'الزيتون وزيت الزيتون', 'Olives & Huile', 'olives', 3),
  ('cat_fruits', 'الفواكه', 'الفواكه', 'Fruits', 'fruits', 4),
  ('cat_honey', 'العسل والمنتوجات المحلية', 'العسل والمنتجات المجالية', 'Miel & Terroir', 'honey', 5),
  ('cat_dairy', 'الحليب والبيض', 'الحليب والبيض', 'Lait & Œufs', 'dairy', 6),
  ('cat_livestock', 'الماشية والدواجن', 'المواشي والدواجن', 'Bétail & Volaille', 'livestock', 7),
  ('cat_cereals', 'الحبوب والقطاني', 'الحبوب والقطنيات', 'Céréales & Légumineuses', 'cereals', 8)
on conflict (id) do nothing;

insert into public.products (id, category_id, name_darija, name_ar, name_fr, allowed_units, image_key) values
  ('prod_tomato', 'cat_veg', 'مطيشة', 'طماطم', 'Tomates', array['kg','crate','ton'], 'tomatoes'),
  ('prod_potato', 'cat_veg', 'بطاطا', 'بطاطس', 'Pommes de terre', array['kg','crate','ton'], 'tomatoes'),
  ('prod_onion', 'cat_veg', 'البصلة', 'بصل', 'Oignons', array['kg','crate','ton'], 'tomatoes'),
  ('prod_pepper', 'cat_veg', 'الفلفلة', 'فلفل', 'Poivrons', array['kg','crate'], 'tomatoes'),
  ('prod_zucchini', 'cat_veg', 'الكرعة الخضراء', 'قرع أخضر', 'Courgettes', array['kg','crate'], 'tomatoes'),
  ('prod_clementine', 'cat_citrus', 'كليمونتين سوس', 'كليمونتين', 'Clémentines', array['kg','crate','ton'], 'citrus'),
  ('prod_orange', 'cat_citrus', 'الليمون (البرتقال)', 'برتقال', 'Oranges', array['kg','crate','ton'], 'citrus'),
  ('prod_lemon', 'cat_citrus', 'الحامض', 'ليمون حامض', 'Citrons', array['kg','crate'], 'citrus'),
  ('prod_olive_oil', 'cat_olives', 'زيت العود', 'زيت الزيتون', 'Huile d''olive', array['liter','ton'], 'citrus'),
  ('prod_olives_green', 'cat_olives', 'الزيتون الخضر', 'زيتون أخضر', 'Olives vertes', array['kg','crate','ton'], 'citrus'),
  ('prod_olives_black', 'cat_olives', 'الزيتون الكحل', 'زيتون أسود', 'Olives noires', array['kg','crate','ton'], 'citrus'),
  ('prod_banana', 'cat_fruits', 'البنان البلدي', 'موز محلي', 'Bananes locales', array['kg','crate'], 'citrus'),
  ('prod_dates', 'cat_fruits', 'التمر المجهول', 'تمر المجهول', 'Dattes Medjool', array['kg','crate'], 'honey'),
  ('prod_watermelon', 'cat_fruits', 'الدلاح', 'بطيخ أحمر', 'Pastèques', array['kg','ton','piece'], 'tomatoes'),
  ('prod_honey', 'cat_honey', 'العسل الحر', 'عسل طبيعي', 'Miel pur', array['kg','liter'], 'honey'),
  ('prod_argan_oil', 'cat_honey', 'زيت أركان للأكل', 'زيت أركان الغذائي', 'Huile d''argane alimentaire', array['liter'], 'honey'),
  ('prod_almonds', 'cat_honey', 'اللوز البلدي', 'لوز بلدي', 'Amandes locales', array['kg','ton'], 'honey'),
  ('prod_eggs', 'cat_dairy', 'البيض البلدي', 'بيض بلدي', 'Œufs fermiers', array['crate','piece'], 'honey'),
  ('prod_milk', 'cat_dairy', 'الحليب الطري', 'حليب طازج', 'Lait frais', array['liter'], 'honey'),
  ('prod_butter', 'cat_dairy', 'الزبدة البلدية', 'زبدة بلدية', 'Beurre fermier', array['kg'], 'honey'),
  ('prod_sheep', 'cat_livestock', 'الحولي / الغنم', 'أغنام', 'Ovins / Moutons', array['head'], 'hero'),
  ('prod_chicken_beldi', 'cat_livestock', 'الدجاج البلدي', 'دجاج بلدي', 'Poulet fermier', array['head','kg'], 'hero'),
  ('prod_wheat', 'cat_cereals', 'القمح الصلب', 'قمح صلب', 'Blé dur', array['kg','ton'], 'honey'),
  ('prod_lentils', 'cat_cereals', 'العدس البلدي', 'عدس بلدي', 'Lentilles locales', array['kg','ton'], 'honey')
on conflict (id) do nothing;

-- Product Attribute Schemas (matching dynamic form schemas in Android SeedData)
insert into public.product_attribute_schemas (product_id, schema_json, version)
select
  p.id,
  case
    when p.category_id in ('cat_veg', 'cat_fruits') then '[
      {"key":"variety","labelDarija":"النوع","labelAr":"الصنف","labelFr":"Variété","type":"select","required":true,"options":["بلدي","رومي","ممتاز"]},
      {"key":"grade","labelDarija":"الجودة","labelAr":"الجودة","labelFr":"Grade","type":"select","required":true,"options":["A","B","C"]},
      {"key":"calibre","labelDarija":"الحجم","labelAr":"العيار","labelFr":"Calibre","type":"select","required":false,"options":["كبير","متوسط","صغير"]}
    ]'::jsonb
    when p.category_id = 'cat_citrus' then '[
      {"key":"variety","labelDarija":"النوع","labelAr":"الصنف","labelFr":"Variété","type":"select","required":true,"options":["نافل","كليمونتين","نادوركوت","ماروك ليت"]},
      {"key":"grade","labelDarija":"الجودة","labelAr":"الجودة","labelFr":"Grade","type":"select","required":true,"options":["A","B","C"]},
      {"key":"calibre","labelDarija":"العيار","labelAr":"العيار","labelFr":"Calibre","type":"select","required":false,"options":["1 (كبير)","2 (متوسط)","3 (صغير)"]}
    ]'::jsonb
    when p.category_id = 'cat_olives' then '[
      {"key":"variety","labelDarija":"النوع","labelAr":"الصنف","labelFr":"Variété","type":"select","required":true,"options":["بيشولين مغربية","منارة","حوزية","بلدي"]},
      {"key":"acidity","labelDarija":"الحموضة (%)","labelAr":"نسبة الحموضة (%)","labelFr":"Acidité (%)","type":"number","required":false,"min":0.1,"max":5.0},
      {"key":"pressing","labelDarija":"العصرة","labelAr":"طريقة العصر","labelFr":"Extraction","type":"select","required":false,"options":["عصرة باردة","معصرة تقليدية"]}
    ]'::jsonb
    when p.id = 'prod_eggs' then '[
      {"key":"egg_type","labelDarija":"النوع","labelAr":"النوع","labelFr":"Type","type":"select","required":true,"options":["بلدي","رومي"]},
      {"key":"size","labelDarija":"الحجم","labelAr":"الحجم","labelFr":"Calibre","type":"select","required":true,"options":["كبير","متوسط","صغير"]},
      {"key":"per_tray","labelDarija":"عدد البيضات فـ البلاطو","labelAr":"العدد في الطبق","labelFr":"Œufs par plateau","type":"number","required":true,"min":6,"max":60}
    ]'::jsonb
    when p.id in ('prod_honey', 'prod_argan_oil') then '[
      {"key":"flora","labelDarija":"نوع العسل","labelAr":"مصدر الرحيق","labelFr":"Origine florale","type":"select","required":true,"options":["الدغموس","الزعتر","السدر","أعشاب الأطلس","الليمون"]},
      {"key":"certified","labelDarija":"شهادة ONSSA","labelAr":"شهادة السلامة الصحية","labelFr":"Certifié ONSSA","type":"boolean","required":false}
    ]'::jsonb
    when p.category_id = 'cat_livestock' then '[
      {"key":"breed","labelDarija":"السلالة","labelAr":"السلالة","labelFr":"Race","type":"select","required":true,"options":["صردي","الدمان","تيمحضيت","بلدي"]},
      {"key":"sex","labelDarija":"الجنس","labelAr":"الجنس","labelFr":"Sexe","type":"select","required":true,"options":["ذكر","أنثى"]},
      {"key":"age_months","labelDarija":"العمر (شهور)","labelAr":"العمر (أشهر)","labelFr":"Âge (mois)","type":"number","required":true,"min":1,"max":120},
      {"key":"weight_kg","labelDarija":"الوزن التقريبي (كيلو)","labelAr":"الوزن التقريبي (كلغ)","labelFr":"Poids approx. (kg)","type":"number","required":true,"min":1,"max":1200}
    ]'::jsonb
    else '[
      {"key":"origin","labelDarija":"المصدر","labelAr":"المصدر","labelFr":"Origine","type":"select","required":true,"options":["بوري","مسقي"]},
      {"key":"grade","labelDarija":"الجودة","labelAr":"الجودة","labelFr":"Qualité","type":"select","required":true,"options":["A","B"]},
      {"key":"cleaned","labelDarija":"منقي ومغربل","labelAr":"منقى ومغربل","labelFr":"Trié & nettoyé","type":"boolean","required":false}
    ]'::jsonb
  end as schema_json,
  1 as version
from public.products p
on conflict (product_id, version) do nothing;

-- Sample wholesale reference prices for Souss-Massa
insert into public.reference_prices (product_id, region_id, unit, price_mad, recorded_date, source)
select v.product_id, v.region_id, v.unit, v.price_mad, current_date, v.source
from (values
  ('prod_tomato', 'reg_souss_massa', 'crate', 84.0::numeric, 'wholesale_market'),
  ('prod_clementine', 'reg_souss_massa', 'kg', 4.3::numeric, 'wholesale_market'),
  ('prod_olive_oil', 'reg_souss_massa', 'liter', 77.0::numeric, 'wholesale_market'),
  ('prod_honey', 'reg_souss_massa', 'kg', 235.0::numeric, 'wholesale_market')
) as v(product_id, region_id, unit, price_mad, source)
where not exists (
  select 1 from public.reference_prices rp
  where rp.product_id = v.product_id
    and rp.region_id = v.region_id
    and rp.recorded_date = current_date
);
