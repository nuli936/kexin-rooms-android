import Foundation
import SwiftSoup

enum SchoolError: LocalizedError {
    case message(String)
    case authExpired
    case captchaRequired

    var errorDescription: String? {
        switch self {
        case .message(let text): return text
        case .authExpired: return "登录已失效，请重新登录。"
        case .captchaRequired: return "学校要求验证码，请输入后登录。"
        }
    }
}

struct SchoolOption: Identifiable, Equatable {
    let id: String
    let label: String
}

struct SchoolPeriod: Identifiable, Equatable {
    let number: Int
    let time: String
    var id: Int { number }
    var label: String { "第\(number)节  \(time)" }
}

struct SchoolRoom: Identifiable {
    let id: String
    let name: String
    let building: String
    let type: String
    let seats: String
    let bookable: String
}

struct SchoolConfig: Equatable {
    let year: String
    let term: String
    let label: String
    let start: Date
    let firstWeek: Int
    let weeks: Set<Int>
    let buildings: [SchoolOption]
    let types: [SchoolOption]
    let periods: [SchoolPeriod]

    func week(for date: Date) throws -> Int {
        let startDay = SchoolProtocol.calendar.startOfDay(for: start)
        let selected = SchoolProtocol.calendar.startOfDay(for: date)
        let days = SchoolProtocol.calendar.dateComponents([.day], from: startDay, to: selected).day ?? -1
        let week = (days / 7) + firstWeek
        guard days >= 0, weeks.contains(week) else {
            throw SchoolError.message("所选日期不在本学期可查询周次内，请更换日期。")
        }
        return week
    }
}

enum SchoolProtocol {
    static let origin = "https://jwglxxfwpt.hebeu.edu.cn"
    static let campus = "106"
    static let roomsPath = "/cdjy/cdjy_cxKxcdlb.html?gnmkdm=N2155"
    static var calendar: Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        cal.firstWeekday = 2
        return cal
    }

    static func string(_ value: Any?) -> String {
        if let text = value as? String { return text }
        if let number = value as? NSNumber { return number.stringValue }
        return ""
    }

    static func object(_ data: Data) throws -> [String: Any] {
        guard let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw SchoolError.message("学校返回了无法识别的数据，本次未显示结果。")
        }
        return value
    }

    static func document(_ html: String) throws -> Document {
        let doc = try SwiftSoup.parse(html)
        if try doc.select("input[name=yhm]").first() != nil { throw SchoolError.authExpired }
        return doc
    }

    static func value(_ doc: Document, _ id: String) throws -> String {
        guard let element = try doc.getElementById(id) else {
            throw SchoolError.message("学校页面字段已变更（\(id)），本次未显示查询结果。")
        }
        let value = try element.val()
        guard !value.isEmpty else { throw SchoolError.message("学校页面字段缺失（\(id)）。") }
        return value
    }

    static func config(html: String, metadata: [String: Any], termData: [String: Any]) throws -> SchoolConfig {
        let doc = try document(html)
        guard let campusOption = try doc.select("select#xqh_id option[value=106]").first(),
              try campusOption.text().contains("科信") else {
            throw SchoolError.message("无法确认科信校区，已停止查询。")
        }
        let year = try value(doc, "xnm")
        let term = try value(doc, "xqm")
        guard let selected = try doc.select("#dm_cx option[value=\(year)-\(term)]").first() else {
            throw SchoolError.message("无法确认学校当前学期。")
        }
        guard let dates = termData["dqzcxq"] as? [String: Any],
              let start = dateFormatter.date(from: string(dates["ZXRQ"])),
              string(dates["ZQST"]) == "1",
              calendar.component(.weekday, from: start) == 2 else {
            throw SchoolError.message("学校校历结构变化，需重新适配。")
        }
        var weeks = Set<Int>()
        guard let weekItems = termData["nxqzcList"] as? [[String: Any]] else {
            throw SchoolError.message("学校没有返回有效周次。")
        }
        for item in weekItems where string(item["zczt"]) != "0" {
            if let number = Int(string(item["dxqzc"])) { weeks.insert(number) }
        }
        guard !weeks.isEmpty, (weeks.max() ?? 0) <= 52,
              let firstWeek = Int(string(dates["ZXZC"])) else {
            throw SchoolError.message("学校没有返回有效周次。")
        }
        var buildings = [SchoolOption(id: "", label: "全部教学楼")]
        guard let buildingItems = metadata["lhList"] as? [[String: Any]] else {
            throw SchoolError.message("教学楼配置缺失。")
        }
        for item in buildingItems {
            let area = string(item["XQH_ID"])
            guard area.isEmpty || area == campus else { throw SchoolError.message("教学楼校区不匹配。") }
            buildings.append(SchoolOption(id: string(item["JXLDM"]), label: string(item["JXLMC"])))
        }
        var types = [SchoolOption]()
        for option in try doc.select("#cdlb_id option").array() {
            let id = try option.val()
            types.append(SchoolOption(id: id, label: id.isEmpty ? "全部场地类别" : try option.text()))
        }
        guard let periodItems = metadata["jcList"] as? [[String: Any]] else {
            throw SchoolError.message("学校没有返回上课节次。")
        }
        var periods = [SchoolPeriod]()
        for item in periodItems {
            guard let number = Int(string(item["JCMC"])), (1...30).contains(number) else {
                throw SchoolError.message("学校返回无效节次。")
            }
            periods.append(SchoolPeriod(number: number, time: string(item["SJD"])))
        }
        guard !periods.isEmpty, !types.isEmpty else {
            throw SchoolError.message("学校查询配置不完整。")
        }
        return SchoolConfig(year: year, term: term, label: try selected.text(), start: start,
                            firstWeek: firstWeek, weeks: weeks, buildings: buildings, types: types, periods: periods)
    }

    static func query(_ config: SchoolConfig, date: Date, from: Int, to: Int,
                      building: String, type: String, page: Int) throws -> [(String, String)] {
        guard from >= 1, to <= 30, from <= to else { throw SchoolError.message("结束节次不能早于开始节次。") }
        let valid = Set(config.periods.map(\.number))
        var bits: UInt64 = 0
        for number in from...to {
            guard valid.contains(number) else { throw SchoolError.message("节次不在学校配置中。") }
            bits |= UInt64(1) << UInt64(number - 1)
        }
        guard config.buildings.contains(where: { $0.id == building }),
              config.types.contains(where: { $0.id == type }) else {
            throw SchoolError.message("查询条件已经失效，请刷新。")
        }
        let week = try config.week(for: date)
        let weekday = ((calendar.component(.weekday, from: date) + 5) % 7) + 1
        var values: [(String, String)] = [
            ("xqh_id", campus), ("xnm", config.year), ("xqm", config.term), ("jyfs", "0"),
            ("zcd", String(UInt64(1) << UInt64(week - 1))), ("xqj", String(weekday)),
            ("jcd", String(bits)), ("lh", building), ("cdlb_id", type)
        ]
        for key in ["cdejlb_id", "qszws", "jszws", "cdmc", "cdjylx", "sfbhkc"] { values.append((key, "")) }
        values += [("queryModel.showCount", "100"), ("queryModel.currentPage", String(page)),
                   ("queryModel.sortName", "cdbh"), ("queryModel.sortOrder", "asc"),
                   ("_search", "false"), ("nd", String(Int(Date().timeIntervalSince1970 * 1000)))]
        return values
    }

    static func page(_ json: [String: Any]) throws -> (total: Int, pages: Int, current: Int, rooms: [SchoolRoom]) {
        guard let total = Int(string(json["totalCount"])), (0...3000).contains(total),
              let pages = Int(string(json["totalPage"])), (0...30).contains(pages),
              let current = Int(string(json["currentPage"])), current >= 1,
              let items = json["items"] as? [[String: Any]] else {
            throw SchoolError.message("学校返回的分页信息异常。")
        }
        var rooms = [SchoolRoom]()
        for item in items {
            guard string(item["xqh_id"]) == campus, string(item["xqmc"]).contains("科信") else {
                throw SchoolError.message("返回了非科信校区数据，已拒绝显示。")
            }
            let id = string(item["cd_id"]), name = string(item["cdmc"])
            guard !id.isEmpty, !name.isEmpty else { throw SchoolError.message("学校返回的教室信息不完整。") }
            rooms.append(SchoolRoom(id: id, name: name,
                                    building: string(item["jxlmc"]).isEmpty ? "未标注楼号" : string(item["jxlmc"]),
                                    type: string(item["cdlbmc"]).isEmpty ? "未标注类型" : string(item["cdlbmc"]),
                                    seats: string(item["zws"]).isEmpty ? "未提供" : string(item["zws"]),
                                    bookable: string(item["sfkjy"]).isEmpty ? "未提供" : string(item["sfkjy"])))
        }
        return (total, pages, current, rooms)
    }

    private static var dateFormatter: DateFormatter {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }
}
