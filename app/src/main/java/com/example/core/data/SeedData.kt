package com.example.core.data

import java.time.LocalDate

object SeedData {
    suspend fun seedIfEmpty(dao: FellahDao) {
        if (dao.countCategories() > 0) return

        val today = LocalDate.now().toEpochDay()
        val now = System.currentTimeMillis()

        // 1. Regions (Souss-Massa default + extensible regions)
        val regions = listOf(
            RegionEntity(
                id = "reg_souss_massa",
                nameDarija = "سوس ماسة",
                nameAr = "سوس ماسة",
                nameFr = "Souss-Massa",
                citiesCsv = "أكادير,تارودانت,اشتوكة أيت باها,إنزكان,أولاد تايمة,تيزنيت",
                isDefault = true
            ),
            RegionEntity(
                id = "reg_marrakech",
                nameDarija = "مراكش آسفي",
                nameAr = "مراكش آسفي",
                nameFr = "Marrakech-Safi",
                citiesCsv = "مراكش,قلعة السراغنة,شيشاوة,الصويرة",
                isDefault = false
            ),
            RegionEntity(
                id = "reg_casablanca",
                nameDarija = "الدار البيضاء سطات",
                nameAr = "الدار البيضاء سطات",
                nameFr = "Casablanca-Settat",
                citiesCsv = "الدار البيضاء,برشيد,سطات,الجديدة",
                isDefault = false
            )
        )
        regions.forEach { dao.upsertRegion(it) }

        // 2. Categories (8 core agricultural categories)
        val categories = listOf(
            CategoryEntity("cat_veg", "الخضراوات", "الخضروات", "Légumes", "veg", 1),
            CategoryEntity("cat_citrus", "الحوامض", "الحوامض", "Agrumes", "citrus", 2),
            CategoryEntity("cat_olives", "الزيتون وزيت العود", "الزيتون وزيت الزيتون", "Olives & Huile", "olives", 3),
            CategoryEntity("cat_fruits", "الفواكه", "الفواكه", "Fruits", "fruits", 4),
            CategoryEntity("cat_honey", "العسل والمنتوجات المحلية", "العسل والمنتجات المجالية", "Miel & Terroir", "honey", 5),
            CategoryEntity("cat_dairy", "الحليب والبيض", "الحليب والبيض", "Lait & Œufs", "dairy", 6),
            CategoryEntity("cat_livestock", "الماشية والدواجن", "المواشي والدواجن", "Bétail & Volaille", "livestock", 7),
            CategoryEntity("cat_cereals", "الحبوب والقطاني", "الحبوب والقطنيات", "Céréales & Légumineuses", "cereals", 8)
        )
        categories.forEach { dao.upsertCategory(it) }

        // Reusable JSON schemas
        val produceSchema = """
            [
              {"key":"variety","labelDarija":"النوع","labelAr":"الصنف","labelFr":"Variété","type":"select","required":true,"options":["بلدي","رومي","ممتاز"]},
              {"key":"grade","labelDarija":"الجودة","labelAr":"الجودة","labelFr":"Grade","type":"select","required":true,"options":["A","B","C"]},
              {"key":"calibre","labelDarija":"الحجم","labelAr":"العيار","labelFr":"Calibre","type":"select","required":false,"options":["كبير","متوسط","صغير"]}
            ]
        """.trimIndent()

        val citrusSchema = """
            [
              {"key":"variety","labelDarija":"النوع","labelAr":"الصنف","labelFr":"Variété","type":"select","required":true,"options":["نافل","كليمونتين","نادوركوت","ماروك ليت"]},
              {"key":"grade","labelDarija":"الجودة","labelAr":"الجودة","labelFr":"Grade","type":"select","required":true,"options":["A","B","C"]},
              {"key":"calibre","labelDarija":"العيار","labelAr":"العيار","labelFr":"Calibre","type":"select","required":false,"options":["1 (كبير)","2 (متوسط)","3 (صغير)"]}
            ]
        """.trimIndent()

        val oliveSchema = """
            [
              {"key":"variety","labelDarija":"النوع","labelAr":"الصنف","labelFr":"Variété","type":"select","required":true,"options":["بيشولين مغربية","منارة","حوزية","بلدي"]},
              {"key":"acidity","labelDarija":"الحموضة (%)","labelAr":"نسبة الحموضة (%)","labelFr":"Acidité (%)","type":"number","required":false,"min":0.1,"max":5.0},
              {"key":"pressing","labelDarija":"العصرة","labelAr":"طريقة العصر","labelFr":"Extraction","type":"select","required":false,"options":["عصرة باردة","معصرة تقليدية"]}
            ]
        """.trimIndent()

        val livestockSchema = """
            [
              {"key":"breed","labelDarija":"السلالة","labelAr":"السلالة","labelFr":"Race","type":"select","required":true,"options":["صردي","الدمان","تيمحضيت","بلدي"]},
              {"key":"sex","labelDarija":"الجنس","labelAr":"الجنس","labelFr":"Sexe","type":"select","required":true,"options":["ذكر","أنثى"]},
              {"key":"age_months","labelDarija":"العمر (شهور)","labelAr":"العمر (أشهر)","labelFr":"Âge (mois)","type":"number","required":true,"min":1,"max":120},
              {"key":"weight_kg","labelDarija":"الوزن التقريبي (كيلو)","labelAr":"الوزن التقريبي (كلغ)","labelFr":"Poids approx. (kg)","type":"number","required":true,"min":1,"max":1200}
            ]
        """.trimIndent()

        val eggsSchema = """
            [
              {"key":"egg_type","labelDarija":"النوع","labelAr":"النوع","labelFr":"Type","type":"select","required":true,"options":["بلدي","رومي"]},
              {"key":"size","labelDarija":"الحجم","labelAr":"الحجم","labelFr":"Calibre","type":"select","required":true,"options":["كبير","متوسط","صغير"]},
              {"key":"per_tray","labelDarija":"عدد البيضات فـ البلاطو","labelAr":"العدد في الطبق","labelFr":"Œufs par plateau","type":"number","required":true,"min":6,"max":60}
            ]
        """.trimIndent()

        val honeySchema = """
            [
              {"key":"flora","labelDarija":"نوع العسل","labelAr":"مصدر الرحيق","labelFr":"Origine florale","type":"select","required":true,"options":["الدغموس","الزعتر","السدر","أعشاب الأطلس","الليمون"]},
              {"key":"certified","labelDarija":"شهادة ONSSA","labelAr":"شهادة السلامة الصحية","labelFr":"Certifié ONSSA","type":"boolean","required":false}
            ]
        """.trimIndent()

        val cerealSchema = """
            [
              {"key":"origin","labelDarija":"المصدر","labelAr":"المصدر","labelFr":"Origine","type":"select","required":true,"options":["بوري","مسقي"]},
              {"key":"grade","labelDarija":"الجودة","labelAr":"الجودة","labelFr":"Qualité","type":"select","required":true,"options":["A","B"]},
              {"key":"cleaned","labelDarija":"منقي ومغربل","labelAr":"منقى ومغربل","labelFr":"Trié & nettoyé","type":"boolean","required":false}
            ]
        """.trimIndent()

        // 3. 24 Seeded Products across all 8 categories
        val products = listOf(
            // Vegetables (5)
            ProductEntity("prod_tomato", "cat_veg", "مطيشة", "طماطم", "Tomates", "kg,crate,ton", produceSchema, "tomatoes"),
            ProductEntity("prod_potato", "cat_veg", "بطاطا", "بطاطس", "Pommes de terre", "kg,crate,ton", produceSchema, "tomatoes"),
            ProductEntity("prod_onion", "cat_veg", "البصلة", "بصل", "Oignons", "kg,crate,ton", produceSchema, "tomatoes"),
            ProductEntity("prod_pepper", "cat_veg", "الفلفلة", "فلفل", "Poivrons", "kg,crate", produceSchema, "tomatoes"),
            ProductEntity("prod_zucchini", "cat_veg", "الكرعة الخضراء", "قرع أخضر", "Courgettes", "kg,crate", produceSchema, "tomatoes"),
            // Citrus (3)
            ProductEntity("prod_clementine", "cat_citrus", "كليمونتين سوس", "كليمونتين", "Clémentines", "kg,crate,ton", citrusSchema, "citrus"),
            ProductEntity("prod_orange", "cat_citrus", "الليمون (البرتقال)", "برتقال", "Oranges", "kg,crate,ton", citrusSchema, "citrus"),
            ProductEntity("prod_lemon", "cat_citrus", "الحامض", "ليمون حامض", "Citrons", "kg,crate", citrusSchema, "citrus"),
            // Olives & Oil (3)
            ProductEntity("prod_olive_oil", "cat_olives", "زيت العود", "زيت الزيتون", "Huile d'olive", "liter,ton", oliveSchema, "citrus"),
            ProductEntity("prod_olives_green", "cat_olives", "الزيتون الخضر", "زيتون أخضر", "Olives vertes", "kg,crate,ton", oliveSchema, "citrus"),
            ProductEntity("prod_olives_black", "cat_olives", "الزيتون الكحل", "زيتون أسود", "Olives noires", "kg,crate,ton", oliveSchema, "citrus"),
            // Fruits (3)
            ProductEntity("prod_banana", "cat_fruits", "البنان البلدي", "موز محلي", "Bananes locales", "kg,crate", produceSchema, "citrus"),
            ProductEntity("prod_dates", "cat_fruits", "التمر المجهول", "تمر المجهول", "Dattes Medjool", "kg,crate", produceSchema, "honey"),
            ProductEntity("prod_watermelon", "cat_fruits", "الدلاح", "بطيخ أحمر", "Pastèques", "kg,ton,piece", produceSchema, "tomatoes"),
            // Honey & Local Products (3)
            ProductEntity("prod_honey", "cat_honey", "العسل الحر", "عسل طبيعي", "Miel pur", "kg,liter", honeySchema, "honey"),
            ProductEntity("prod_argan_oil", "cat_honey", "زيت أركان للأكل", "زيت أركان الغذائي", "Huile d'argane alimentaire", "liter", honeySchema, "honey"),
            ProductEntity("prod_almonds", "cat_honey", "اللوز البلدي", "لوز بلدي", "Amandes locales", "kg,ton", cerealSchema, "honey"),
            // Dairy & Eggs (3)
            ProductEntity("prod_eggs", "cat_dairy", "البيض البلدي", "بيض بلدي", "Œufs fermiers", "crate,piece", eggsSchema, "honey"),
            ProductEntity("prod_milk", "cat_dairy", "الحليب الطري", "حليب طازج", "Lait frais", "liter", cerealSchema, "honey"),
            ProductEntity("prod_butter", "cat_dairy", "الزبدة البلدية", "زبدة بلدية", "Beurre fermier", "kg", cerealSchema, "honey"),
            // Livestock & Poultry (2)
            ProductEntity("prod_sheep", "cat_livestock", "الحولي / الغنم", "أغنام", "Ovins / Moutons", "head", livestockSchema, "hero"),
            ProductEntity("prod_chicken_beldi", "cat_livestock", "الدجاج البلدي", "دجاج بلدي", "Poulet fermier", "head,kg", livestockSchema, "hero"),
            // Cereals & Legumes (2)
            ProductEntity("prod_wheat", "cat_cereals", "القمح الصلب", "قمح صلب", "Blé dur", "kg,ton", cerealSchema, "honey"),
            ProductEntity("prod_lentils", "cat_cereals", "العدس البلدي", "عدس بلدي", "Lentilles locales", "kg,ton", cerealSchema, "honey")
        )
        products.forEach { dao.upsertProduct(it) }

        // 4. Sample Moroccan Users (Farmers, Buyers, Pending Verification, Admin)
        val profiles = listOf(
            ProfileEntity(
                id = "farmer_lahcen",
                phone = "0661112233",
                fullName = "الحاج لحسن أيت باها",
                role = UserRole.FARMER,
                verificationStatus = VerificationStatus.VERIFIED,
                regionId = "reg_souss_massa",
                city = "اشتوكة أيت باها",
                ratingAvg = 4.8,
                ratingCount = 14
            ),
            ProfileEntity(
                id = "farmer_mariam",
                phone = "0662334455",
                fullName = "مريم تاوريرت",
                role = UserRole.FARMER,
                verificationStatus = VerificationStatus.VERIFIED,
                regionId = "reg_souss_massa",
                city = "تارودانت",
                ratingAvg = 4.9,
                ratingCount = 9
            ),
            ProfileEntity(
                id = "farmer_pending",
                phone = "0668990011",
                fullName = "عبد الرحيم السوسي",
                role = UserRole.FARMER,
                verificationStatus = VerificationStatus.PENDING,
                regionId = "reg_souss_massa",
                city = "أولاد تايمة",
                ratingAvg = 0.0,
                ratingCount = 0
            ),
            ProfileEntity(
                id = "buyer_tafraout",
                phone = "0663556677",
                fullName = "كريم أمرابط",
                role = UserRole.BUYER,
                verificationStatus = VerificationStatus.VERIFIED,
                regionId = "reg_souss_massa",
                city = "أكادير",
                ratingAvg = 4.7,
                ratingCount = 11
            ),
            ProfileEntity(
                id = "buyer_inzegane",
                phone = "0664778899",
                fullName = "يوسف البقال",
                role = UserRole.BUYER,
                verificationStatus = VerificationStatus.VERIFIED,
                regionId = "reg_souss_massa",
                city = "إنزكان",
                ratingAvg = 4.5,
                ratingCount = 6
            ),
            ProfileEntity(
                id = "admin_fellah",
                phone = "0660000001",
                fullName = "سلمى الإدريسي (الإدارة)",
                role = UserRole.ADMIN,
                verificationStatus = VerificationStatus.VERIFIED,
                regionId = "reg_souss_massa",
                city = "أكادير",
                ratingAvg = 5.0,
                ratingCount = 1
            )
        )
        profiles.forEach { dao.upsertProfile(it) }

        dao.upsertFarmerDetails(
            FarmerDetailsEntity("farmer_lahcen", "ضيعة أيت باها للخضر", "cat_veg,cat_citrus")
        )
        dao.upsertFarmerDetails(
            FarmerDetailsEntity("farmer_mariam", "تعاونية تافوكت أركان وعسل", "cat_honey,cat_olives,cat_fruits")
        )
        dao.upsertFarmerDetails(
            FarmerDetailsEntity("farmer_pending", "ضيعة النخيل", "cat_citrus")
        )

        dao.upsertBuyerDetails(
            BuyerDetailsEntity("buyer_tafraout", "مطعم تافراوت أكادير", "restaurant", "cat_veg,cat_olives,cat_honey")
        )
        dao.upsertBuyerDetails(
            BuyerDetailsEntity("buyer_inzegane", "شركة سوس للجملة", "wholesaler", "cat_veg,cat_citrus,cat_fruits")
        )

        // 5. Sample Active Offers
        val offers = listOf(
            OfferEntity(
                id = "offer_1",
                farmerId = "farmer_lahcen",
                productId = "prod_tomato",
                categoryId = "cat_veg",
                regionId = "reg_souss_massa",
                city = "اشتوكة أيت باها",
                quantity = 120.0,
                unit = "crate",
                askingPrice = 85.0,
                readyDateEpochDay = today,
                attributesJson = """{"variety":"ممتاز","grade":"A","calibre":"كبير"}""",
                photoUrisCsv = "drawable:tomatoes",
                status = OfferStatus.ACTIVE,
                createdAt = now - 3_600_000L
            ),
            OfferEntity(
                id = "offer_2",
                farmerId = "farmer_lahcen",
                productId = "prod_clementine",
                categoryId = "cat_citrus",
                regionId = "reg_souss_massa",
                city = "تارودانت",
                quantity = 2500.0,
                unit = "kg",
                askingPrice = 4.5,
                readyDateEpochDay = today + 1,
                attributesJson = """{"variety":"كليمونتين","grade":"A","calibre":"2 (متوسط)"}""",
                photoUrisCsv = "drawable:citrus",
                status = OfferStatus.ACTIVE,
                createdAt = now - 7_200_000L
            ),
            OfferEntity(
                id = "offer_3",
                farmerId = "farmer_mariam",
                productId = "prod_olive_oil",
                categoryId = "cat_olives",
                regionId = "reg_souss_massa",
                city = "تارودانت",
                quantity = 400.0,
                unit = "liter",
                askingPrice = 78.0,
                readyDateEpochDay = today,
                attributesJson = """{"variety":"بيشولين مغربية","acidity":"0.6","pressing":"عصرة باردة"}""",
                photoUrisCsv = "drawable:citrus",
                status = OfferStatus.ACTIVE,
                createdAt = now - 10_800_000L
            ),
            OfferEntity(
                id = "offer_4",
                farmerId = "farmer_mariam",
                productId = "prod_honey",
                categoryId = "cat_honey",
                regionId = "reg_souss_massa",
                city = "تيزنيت",
                quantity = 65.0,
                unit = "kg",
                askingPrice = 240.0,
                readyDateEpochDay = today,
                attributesJson = """{"flora":"الدغموس","certified":"true"}""",
                photoUrisCsv = "drawable:honey",
                status = OfferStatus.ACTIVE,
                createdAt = now - 14_400_000L
            ),
            OfferEntity(
                id = "offer_5",
                farmerId = "farmer_lahcen",
                productId = "prod_pepper",
                categoryId = "cat_veg",
                regionId = "reg_souss_massa",
                city = "اشتوكة أيت باها",
                quantity = 80.0,
                unit = "crate",
                askingPrice = 95.0,
                readyDateEpochDay = today + 2,
                attributesJson = """{"variety":"رومي","grade":"A"}""",
                photoUrisCsv = "drawable:tomatoes",
                status = OfferStatus.ACTIVE,
                createdAt = now - 20_000_000L
            )
        )
        offers.forEach { dao.upsertOffer(it) }

        // 6. Sample Wanted Requests (Buy Requests)
        dao.upsertBuyRequest(
            BuyRequestEntity(
                id = "req_1",
                buyerId = "buyer_tafraout",
                productId = "prod_tomato",
                categoryId = "cat_veg",
                regionId = "reg_souss_massa",
                city = "أكادير",
                quantity = 40.0,
                unit = "crate",
                maxPrice = 90.0,
                neededDateEpochDay = today + 1,
                notes = "مطلوب مطيشة قاصحة للسلطة يومياً"
            )
        )
        dao.upsertBuyRequest(
            BuyRequestEntity(
                id = "req_2",
                buyerId = "buyer_inzegane",
                productId = "prod_clementine",
                categoryId = "cat_citrus",
                regionId = "reg_souss_massa",
                city = "إنزكان",
                quantity = 4.0,
                unit = "ton",
                maxPrice = 4200.0,
                neededDateEpochDay = today + 3,
                notes = "كليمونتين ديال سوس درجة A"
            )
        )

        // 7. Sample Deals + History + Ratings (shows requested, accepted, and completed)
        val dealAccepted = DealEntity(
            id = "deal_1",
            offerId = "offer_3",
            buyRequestId = null,
            farmerId = "farmer_mariam",
            buyerId = "buyer_tafraout",
            productId = "prod_olive_oil",
            quantity = 60.0,
            unit = "liter",
            proposedPrice = 76.0,
            agreedPrice = 76.0,
            deliveryType = "pickup",
            preferredDateEpochDay = today + 1,
            note = "غادي نجي نهزهم فـ بيدوزات ديال 5 لتر",
            status = DealStatus.ACCEPTED,
            lastActorRole = UserRole.FARMER,
            createdAt = now - 86_400_000L,
            updatedAt = now - 43_200_000L
        )
        dao.upsertDeal(dealAccepted)
        dao.insertDealHistory(
            DealStatusHistoryEntity("dh_1a", "deal_1", DealStatus.REQUESTED, "buyer_tafraout", "طلب شراء 60 لتر", 74.0, now - 86_400_000L)
        )
        dao.insertDealHistory(
            DealStatusHistoryEntity("dh_1b", "deal_1", DealStatus.COUNTERED, "farmer_mariam", "الثمن النهائي 76 درهم", 76.0, now - 60_000_000L)
        )
        dao.insertDealHistory(
            DealStatusHistoryEntity("dh_1c", "deal_1", DealStatus.ACCEPTED, "buyer_tafraout", "موافق على 76 درهم", 76.0, now - 43_200_000L)
        )

        val dealRequested = DealEntity(
            id = "deal_2",
            offerId = "offer_1",
            buyRequestId = null,
            farmerId = "farmer_lahcen",
            buyerId = "buyer_tafraout",
            productId = "prod_tomato",
            quantity = 30.0,
            unit = "crate",
            proposedPrice = 82.0,
            agreedPrice = null,
            deliveryType = "delivery",
            preferredDateEpochDay = today + 1,
            note = "التوصيل لمطعم تافراوت أكادير",
            status = DealStatus.REQUESTED,
            lastActorRole = UserRole.BUYER,
            createdAt = now - 7_200_000L,
            updatedAt = now - 7_200_000L
        )
        dao.upsertDeal(dealRequested)
        dao.insertDealHistory(
            DealStatusHistoryEntity("dh_2a", "deal_2", DealStatus.REQUESTED, "buyer_tafraout", "طلب شراء 30 صندوق", 82.0, now - 7_200_000L)
        )

        val dealCompleted = DealEntity(
            id = "deal_3",
            offerId = "offer_2",
            buyRequestId = null,
            farmerId = "farmer_lahcen",
            buyerId = "buyer_inzegane",
            productId = "prod_clementine",
            quantity = 1000.0,
            unit = "kg",
            proposedPrice = 4.4,
            agreedPrice = 4.4,
            deliveryType = "pickup",
            preferredDateEpochDay = today - 2,
            status = DealStatus.COMPLETED,
            lastActorRole = UserRole.FARMER,
            farmerConfirmedCompletion = true,
            buyerConfirmedCompletion = true,
            createdAt = now - 3 * 86_400_000L,
            updatedAt = now - 2 * 86_400_000L
        )
        dao.upsertDeal(dealCompleted)
        dao.insertDealHistory(
            DealStatusHistoryEntity("dh_3a", "deal_3", DealStatus.ACCEPTED, "farmer_lahcen", "موافقة", 4.4, now - 3 * 86_400_000L)
        )
        dao.insertDealHistory(
            DealStatusHistoryEntity("dh_3b", "deal_3", DealStatus.COMPLETED, "buyer_inzegane", "تم التسليم كاش", 4.4, now - 2 * 86_400_000L)
        )
        dao.insertRating(
            RatingEntity("rat_1", "deal_3", "buyer_inzegane", "farmer_lahcen", 5, "جودة عالية واحترام الوقت")
        )

        // 8. 30-day Reference & Market Price History (Inzegane Wholesale & Souss-Massa)
        val tomatoBase = 84.0
        val clemBase = 4.3
        val oilBase = 77.0
        val honeyBase = 235.0
        for (offset in 29 downTo 0) {
            val day = today - offset
            val wave = ((offset % 5) - 2) * 1.5
            dao.upsertReferencePrice(ReferencePriceEntity("ref_tom_$offset", "prod_tomato", "reg_souss_massa", "crate", tomatoBase + wave, day))
            dao.upsertReferencePrice(ReferencePriceEntity("ref_clm_$offset", "prod_clementine", "reg_souss_massa", "kg", clemBase + (wave * 0.1), day))
            dao.upsertReferencePrice(ReferencePriceEntity("ref_oil_$offset", "prod_olive_oil", "reg_souss_massa", "liter", oilBase + (wave * 0.8), day))
            dao.upsertReferencePrice(ReferencePriceEntity("ref_hny_$offset", "prod_honey", "reg_souss_massa", "kg", honeyBase + (wave * 2.0), day))
        }

        // 9. Notifications, Proposals & Reports
        dao.insertNotification(
            NotificationEntity(
                id = "notif_1",
                userId = "farmer_lahcen",
                type = "new_request",
                titleDarija = "طلب شراء جديد على مطيشة",
                titleAr = "طلب شراء جديد على الطماطم",
                titleFr = "Nouvelle demande sur Tomates",
                bodyText = "مطعم تافراوت بغا 30 صندوق بـ 82 درهم",
                relatedEntityId = "deal_2",
                createdAt = now - 7_200_000L
            )
        )
        dao.insertNotification(
            NotificationEntity(
                id = "notif_2",
                userId = "buyer_tafraout",
                type = "deal_accepted",
                titleDarija = "تم قبول صفقة زيت العود",
                titleAr = "تم قبول صفقة زيت الزيتون",
                titleFr = "Accord accepté pour Huile d'olive",
                bodyText = "تعاونية مريم قبلات العرض بـ 76 درهم/لتر. رقم الهاتف ولا متاح.",
                relatedEntityId = "deal_1",
                createdAt = now - 43_200_000L
            )
        )

        dao.upsertProductProposal(
            ProductProposalEntity(
                id = "prop_1",
                farmerId = "farmer_mariam",
                proposedName = "أملو باللوز وأركان",
                categoryId = "cat_honey",
                suggestedUnit = "kg",
                notes = "مطلوب بزاف من الفنادق فـ أكادير",
                status = "pending"
            )
        )

        dao.upsertReport(
            ReportEntity(
                id = "rep_1",
                reporterId = "buyer_inzegane",
                targetType = "offer",
                targetId = "offer_5",
                reason = "مراجعة تاريخ الجاهزية ديال الفلفلة",
                status = "open"
            )
        )
    }
}
